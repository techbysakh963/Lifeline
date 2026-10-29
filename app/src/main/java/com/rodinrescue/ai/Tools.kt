package com.rodinrescue.ai

import com.rodinrescue.security.CommandPolicy
import kotlinx.serialization.json.*

enum class ToolLevel { DIAGNOSTIC, PRIVILEGED, REPAIR }

data class ToolSpec(val def: ToolDef, val level: ToolLevel)

/** Tool catalog + argument validation + the FIXED command templates behind structured tools (no free-form interpolation). */
object Tools {
    private val unitRe = Regex("^[A-Za-z0-9@._:\\-]{1,100}$")
    private val prios = listOf("emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")

    private fun spec(name: String, desc: String, level: ToolLevel, s: JsonObject) = ToolSpec(ToolDef(name, desc, s), level)

    val all: List<ToolSpec> = listOf(
        spec("get_system_information", "Collect OS, kernel, uptime, default target, memory, CPU and block-device overview. Read-only.", ToolLevel.DIAGNOSTIC, schema()),
        spec("run_readonly_command", "Run ONE read-only shell command on the target (allowlisted programs only; no redirection, substitution or writes). Rejected if it could modify the system.", ToolLevel.DIAGNOSTIC,
            schema(listOf("command", "purpose"), mapOf("command" to strProp("The command line", 1000), "purpose" to strProp("Why you are running it", 300)))),
        spec("request_privileged_command", "Run a command with sudo on the target. ALWAYS needs explicit user approval. Use for privileged READS (e.g. reading root-only logs) or when a repair is not yet formalised.", ToolLevel.PRIVILEGED,
            schema(listOf("command", "purpose", "consequences", "reversible"), mapOf(
                "command" to strProp("Command line (run via sudo sh -c)", 1000), "purpose" to strProp("Why it is needed", 400),
                "consequences" to strProp("What it changes / possible side effects", 400), "reversible" to boolProp("Can the effect be undone?")))),
        spec("read_system_logs", "Read journald logs with fixed, validated filters.", ToolLevel.DIAGNOSTIC,
            schema(emptyList(), mapOf(
                "unit" to strProp("systemd unit, e.g. gdm.service", 100), "priority" to strProp("Max priority level", null, prios),
                "boot_offset" to intProp("0 = current boot, -1 = previous boot", -9, 0), "lines" to intProp("Number of lines (default 100)", 1, 500),
                "grep" to strProp("Case-insensitive extended regex filter", 100)))),
        spec("check_systemd_services", "Inspect systemd services. mode=failed lists failed units; status shows one unit; enabled checks enablement; running lists active services.", ToolLevel.DIAGNOSTIC,
            schema(listOf("mode"), mapOf("mode" to strProp("Mode", null, listOf("failed", "status", "enabled", "running")), "unit" to strProp("Unit name (required for status/enabled)", 100)))),
        spec("inspect_display_manager", "Gather display-manager / session / Xorg / Wayland state and recent related logs. Read-only.", ToolLevel.DIAGNOSTIC, schema()),
        spec("check_graphics_drivers", "Collect GPU, kernel module, DRM and driver (nvidia/amdgpu/i915) state. Read-only.", ToolLevel.DIAGNOSTIC, schema()),
        spec("check_disk_space", "Check disk and inode usage and the biggest directories under /var. Read-only.", ToolLevel.DIAGNOSTIC, schema()),
        spec("inspect_boot_errors", "Boot-level errors: journal errors for this and previous boot, failed units, slow units. Read-only.", ToolLevel.DIAGNOSTIC, schema()),
        spec("suggest_repair", "Propose ONE repair command. Shows the user the problem, evidence, command, rationale, consequences and reversibility, and waits for approval. Does NOT run it.", ToolLevel.REPAIR,
            schema(listOf("suspected_problem", "evidence", "command", "rationale", "consequences", "reversible"), mapOf(
                "suspected_problem" to strProp("Root-cause hypothesis", 500), "evidence" to strProp("Concrete evidence from actual command output", 1000),
                "command" to strProp("Exact command (will run via sudo sh -c)", 1000), "rationale" to strProp("Why this command fixes it", 500),
                "consequences" to strProp("Possible side effects", 500), "reversible" to boolProp("Is it reversible?")))),
        spec("apply_approved_repair", "Execute a repair previously approved by the user via suggest_repair. Fails if the proposal was not approved. Success of the command does NOT prove the problem is fixed.", ToolLevel.REPAIR,
            schema(listOf("proposal_id"), mapOf("proposal_id" to strProp("id returned by suggest_repair", 40)))),
        spec("verify_repair", "Run ONE read-only check that demonstrates whether the problem is actually resolved, and state the expected result.", ToolLevel.DIAGNOSTIC,
            schema(listOf("check_command", "expectation"), mapOf("check_command" to strProp("Read-only command", 1000), "expectation" to strProp("What output means success", 300))))
    )

    val byName = all.associateBy { it.def.name }
    val defs get() = all.map { it.def }

    class Invalid(msg: String) : Exception(msg)

    fun parseArgs(json: String): JsonObject = try {
        (Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject) ?: throw Invalid("arguments must be a JSON object")
    } catch (e: Invalid) { throw e } catch (e: Exception) { throw Invalid("arguments are not valid JSON: ${e.message}") }

    /** Validates against the tool's schema (types, required, lengths, enums, ranges, unknown keys). Returns normalized args. */
    fun validate(tool: ToolSpec, args: JsonObject): JsonObject {
        val sch = tool.def.parameters
        val props = sch["properties"]!!.jsonObject
        val required = sch["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        for (r in required) if (args[r] == null || args[r] is JsonNull) throw Invalid("missing required argument '$r'")
        for ((k, v) in args) {
            val p = props[k]?.jsonObject ?: throw Invalid("unknown argument '$k'")
            when (p["type"]!!.jsonPrimitive.content) {
                "string" -> {
                    val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Invalid("'$k' must be a string")
                    p["maxLength"]?.jsonPrimitive?.int?.let { if (s.length > it) throw Invalid("'$k' longer than $it chars") }
                    p["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.let { if (s !in it) throw Invalid("'$k' must be one of $it") }
                    if (s.contains('\u0000')) throw Invalid("'$k' contains NUL")
                }
                "integer" -> {
                    val i = (v as? JsonPrimitive)?.intOrNull ?: throw Invalid("'$k' must be an integer")
                    if (i < p["minimum"]!!.jsonPrimitive.int || i > p["maximum"]!!.jsonPrimitive.int) throw Invalid("'$k' out of range")
                }
                "boolean" -> (v as? JsonPrimitive)?.booleanOrNull ?: throw Invalid("'$k' must be a boolean")
            }
        }
        return args
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    /** Fixed command lists for structured diagnostic tools. Every entry MUST classify as READ_ONLY (enforced by tests). */
    fun commandsFor(name: String, a: JsonObject): List<String> = when (name) {
        "get_system_information" -> listOf("uname -a", "cat /etc/os-release", "hostnamectl status --no-pager", "uptime", "systemctl get-default",
            "systemctl is-system-running", "free -h", "lscpu | head -n 20", "lsblk -f")
        "read_system_logs" -> {
            val unit = a.str("unit")?.also { if (!unitRe.matches(it)) throw Invalid("invalid unit name") }
            val prio = a.str("priority")
            val boot = a.int("boot_offset") ?: 0
            val lines = a.int("lines") ?: 100
            val g = a.str("grep")
            listOf(buildString {
                append("journalctl -b $boot")
                if (prio != null) append(" -p $prio")
                if (unit != null) append(" -u ${CommandPolicy.shellQuote(unit)}")
                append(" -n $lines --no-pager")
                if (g != null) append(" | grep -E -i -- ${CommandPolicy.shellQuote(g)}")
            })
        }
        "check_systemd_services" -> {
            val unit = a.str("unit")?.also { if (!unitRe.matches(it)) throw Invalid("invalid unit name") }
            when (a.str("mode")) {
                "failed" -> listOf("systemctl --failed --no-pager")
                "running" -> listOf("systemctl list-units --type=service --state=running --no-pager")
                "status" -> listOf("systemctl status ${CommandPolicy.shellQuote(unit ?: throw Invalid("'unit' required for status"))} --no-pager -l")
                "enabled" -> listOf("systemctl is-enabled ${CommandPolicy.shellQuote(unit ?: throw Invalid("'unit' required for enabled"))}")
                else -> throw Invalid("bad mode")
            }
        }
        "inspect_display_manager" -> listOf(
            "systemctl get-default", "cat /etc/X11/default-display-manager",
            "systemctl status display-manager --no-pager -l",
            "systemctl list-units --type=service --no-pager | grep -E -i 'gdm|sddm|lightdm|lxdm|greetd|display-manager'",
            "loginctl list-sessions --no-pager",
            "journalctl -b -n 80 --no-pager -u display-manager -u gdm -u gdm3 -u sddm -u lightdm",
            "ls -la /var/log/Xorg.0.log /var/log/lightdm /var/log/gdm3 /var/log/sddm.log",
            "tail -n 60 /var/log/Xorg.0.log",
            "journalctl -b -p warning -n 60 --no-pager | grep -E -i 'wayland|xorg|gnome-shell|mutter|plasma|kwin|drm|session'")
        "check_graphics_drivers" -> listOf(
            "lspci -k | grep -E -A3 'VGA|3D|Display'", "lsmod | grep -E -i 'nvidia|nouveau|amdgpu|radeon|i915|xe'", "ls -la /dev/dri",
            "nvidia-smi", "dkms status", "mokutil --sb-state",
            "journalctl -k -b -p warning --no-pager -n 120 | grep -E -i 'drm|nvidia|amdgpu|i915|nouveau|gpu|firmware'")
        "check_disk_space" -> listOf("df -h", "df -i", "lsblk -f", "journalctl --disk-usage", "du -xh --max-depth=1 /var 2>/dev/null | sort -rh | head -n 15")
        "inspect_boot_errors" -> listOf(
            "journalctl --list-boots --no-pager", "journalctl -b -p err -n 150 --no-pager", "journalctl -b -1 -p err -n 50 --no-pager",
            "systemctl --failed --no-pager", "systemd-analyze blame | head -n 15", "systemctl is-system-running")
        else -> throw Invalid("tool '$name' has no fixed command template")
    }
}
