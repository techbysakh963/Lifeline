package com.rodinrescue.security

/** Risk classes, ordered from least to most dangerous. */
@kotlinx.serialization.Serializable
enum class Risk { READ_ONLY, MODIFYING, DESTRUCTIVE, FORBIDDEN }

data class Classification(val risk: Risk, val reason: String)

/**
 * Conservative, allowlist-based classifier for shell command lines destined for the target machine.
 *
 * Principle: a command is READ_ONLY only if every pipeline segment starts with a known read-only
 * program and uses no write-capable flag, redirection, command substitution or background operator.
 * Anything unknown is MODIFYING (needs approval). Known-catastrophic patterns are FORBIDDEN
 * (never executed, even if the user approves).
 */
object CommandPolicy {

    private val readOnlyPrograms = setOf(
        "cat", "ls", "head", "tail", "grep", "egrep", "fgrep", "df", "du", "free", "uname", "lsblk",
        "lspci", "lsusb", "lsmod", "ps", "uptime", "id", "whoami", "who", "w", "last", "lastb", "findmnt",
        "blkid", "getenforce", "sestatus", "echo", "wc", "sort", "uniq", "cut", "tr", "stat", "file",
        "readlink", "which", "locale", "date", "hostname", "nproc", "lscpu", "lsmem", "printf", "true",
        "nvidia-smi", "vulkaninfo", "glxinfo", "xrandr", "lsof", "pgrep", "basename", "dirname", "realpath",
        "test", "[", "sha256sum", "md5sum", "mokutil", "efibootmgr", "loginctl", "hostnamectl",
        "timedatectl", "localectl", "resolvectl", "networkctl", "bootctl", "ss", "netstat", "ping",
        "systemctl", "journalctl", "dmesg", "ip", "find", "dpkg", "rpm", "pacman", "apt", "apt-cache",
        "dnf", "yum", "zypper", "snap", "flatpak", "mount", "sysctl", "smartctl", "fsck-ro", "lsattr",
        "getfacl", "env-ro", "ldd", "modinfo", "dkms", "ufw", "iptables", "nft", "docker-ro", "command", "type", "systemd-analyze"
    )

    private val sensitivePath = Regex(
        """(^|[\s=/'"])(/etc/shadow|/etc/gshadow|\.ssh/|id_rsa|id_ed25519|id_ecdsa|\.gnupg|\.pem\b|\.key\b|\.kdbx|/proc/\d+/environ|\.aws/|\.netrc|\.git-credentials)"""
    )

    // Patterns that are never executed.
    private val forbidden: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\s+(-[a-zA-Z]+\s+)*(--no-preserve-root\s+)?(/|/\*|~|~/|${'$'}HOME|/home|/etc|/usr|/var|/boot|/bin|/lib\w*)(\s|$)""") to "recursive delete of a system or home root",
        Regex("""--no-preserve-root""") to "--no-preserve-root",
        Regex("""\bmkfs(\.\w+)?\b""") to "filesystem formatting",
        Regex("""\bwipefs\b""") to "signature wiping",
        Regex("""\b(shred|blkdiscard)\b""") to "irreversible data destruction",
        Regex("""\bdd\b.*\bof=/dev/""") to "raw write to a block device",
        Regex(""">\s*/dev/(sd|nvme|vd|mmcblk|hd)""") to "redirect into a block device",
        Regex("""\b(fdisk|sfdisk|sgdisk|gdisk|cfdisk|parted|cryptsetup\s+(luksFormat|erase|luksErase))\b""") to "partition table / LUKS modification",
        Regex("""\bgrub-?install\b""") to "bootloader installation",
        Regex("""\bflashrom\b|\bfwupdmgr\s+(install|update|downgrade)\b|\bfwupdtool\b""") to "firmware flashing",
        Regex("""\bmokutil\b.*(--import|--delete|--reset|--disable-validation|--enable-validation|--revoke)""") to "Secure Boot key modification",
        Regex(""":\(\)\s*\{""") to "fork bomb",
        Regex("""\b(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba|z|da)?sh\b""") to "piping a download into a shell",
        Regex("""\bchmod\s+(-R\s+)?[0-7]*7[0-7]{0,2}\s+/(\s|$)|\bchown\s+-R\s+\S+\s+/(\s|$)""") to "recursive permission change on /",
        Regex("""\b(mv|cp)\b.*\s/dev/null\b""") to "moving data into /dev/null",
        Regex("""\b(passwd|chpasswd|usermod\s+.*-p)\b""") to "password change (do it manually)",
        Regex("""\bsetenforce\s+0\b|\baa-teardown\b""") to "disabling a mandatory-access-control framework"
    )

    private val destructive = Regex(
        """(^|\s)(rm|rmdir|unlink|userdel|groupdel|truncate|dd|lvremove|vgremove|pvremove|mdadm\s+--(stop|remove|zero))(\s|$)|""" +
            """\b(apt(-get)?|dpkg|dnf|yum|zypper|pacman|snap|flatpak)\b.*(\b(remove|purge|autoremove|erase|--remove|--purge|uninstall)\b|\s-R[a-z]*\b|\s-e\s)|""" +
            """\bmv\b.*\s/(etc|boot|usr|var|bin|lib)"""
    )

    private val systemctlReadOnly = setOf(
        "status", "is-active", "is-enabled", "is-failed", "is-system-running", "list-units", "list-unit-files",
        "list-dependencies", "list-sockets", "list-timers", "list-jobs", "show", "cat", "get-default",
        "--failed", "--version"
    )
    private val journalctlDenied = setOf("--vacuum-size", "--vacuum-time", "--vacuum-files", "--rotate", "--flush", "--sync", "--relinquish-var", "--smart-relinquish-var", "--setup-keys", "--update-catalog")
    private val ipReadOnly = setOf("a", "addr", "address", "l", "link", "r", "route", "n", "neigh", "neighbor", "neighbour", "s", "-s", "-br", "-c", "-4", "-6", "-j", "-d", "-o", "-h")
    private val ipDeniedVerbs = setOf("add", "del", "delete", "flush", "set", "change", "replace", "append", "prepend", "flush")
    private val pkgReadOnly = mapOf(
        "dpkg" to setOf("-l", "--list", "-s", "--status", "-L", "--listfiles", "-S", "--search", "--audit", "-C", "--get-selections", "--print-architecture", "-V", "--verify"),
        "rpm" to setOf("-q", "-qa", "-qi", "-ql", "-qf", "-V", "-Va", "--query", "--verify", "-qR"),
        "pacman" to setOf("-Q", "-Qi", "-Ql", "-Qs", "-Qe", "-Qk", "-Qkk", "-Qm", "-Qdt", "-Qo", "-Qq", "-Qn"),
        "apt" to setOf("list", "show", "policy", "search"),
        "apt-cache" to setOf("policy", "show", "search", "depends", "rdepends", "madison"),
        "dnf" to setOf("list", "info", "repolist", "history", "check", "search", "provides"),
        "yum" to setOf("list", "info", "repolist", "history", "check", "search", "provides"),
        "zypper" to setOf("search", "info", "se", "if", "lr", "ps"),
        "snap" to setOf("list", "info", "services", "changes"),
        "flatpak" to setOf("list", "info", "remotes"),
        "loginctl" to setOf("list-sessions", "list-users", "show-session", "show-user", "session-status", "user-status", "status", "list-seats", "seat-status", "show-seat"),
        "hostnamectl" to setOf("status"), "timedatectl" to setOf("status", "show"), "localectl" to setOf("status"),
        "resolvectl" to setOf("status", "query", "statistics"), "networkctl" to setOf("status", "list", "lldp"),
        "bootctl" to setOf("status", "list"), "dkms" to setOf("status"), "ufw" to setOf("status"),
        "iptables" to setOf("-L", "-S", "-n", "-v", "-t", "--list", "--list-rules", "-nvL"),
        "nft" to setOf("list"), "snap-ro" to emptySet()
    )

    /** Strict word split honoring single/double quotes. Returns null if quoting is unbalanced. */
    internal fun splitSegments(cmd: String): List<List<String>>? {
        val segments = mutableListOf<MutableList<String>>()
        var cur = StringBuilder()
        var words = mutableListOf<String>()
        var inS = false
        var inD = false
        var hadWord = false
        fun endWord() { if (hadWord || cur.isNotEmpty()) { words.add(cur.toString()); cur = StringBuilder(); hadWord = false } }
        fun endSeg() { endWord(); if (words.isNotEmpty()) segments.add(words); words = mutableListOf() }
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            when {
                inS -> if (c == '\'') inS = false else cur.append(c)
                inD -> when {
                    c == '"' -> inD = false
                    c == '\\' && i + 1 < cmd.length -> { cur.append(cmd[i + 1]); i++ }
                    else -> cur.append(c)
                }
                c == '\'' -> { inS = true; hadWord = true }
                c == '"' -> { inD = true; hadWord = true }
                c == '\\' && i + 1 < cmd.length -> { cur.append(cmd[i + 1]); hadWord = true; i++ }
                c.isWhitespace() -> endWord()
                c == '|' -> { endSeg(); if (i + 1 < cmd.length && cmd[i + 1] == '|') i++ }
                c == '&' && i + 1 < cmd.length && cmd[i + 1] == '&' -> { endSeg(); i++ }
                c == ';' -> endSeg()
                else -> cur.append(c)
            }
            i++
        }
        if (inS || inD) return null
        endSeg()
        return segments
    }

    /** True if the raw string has constructs we refuse to reason about for READ_ONLY purposes. */
    private fun hasUnsafeSyntax(cmd: String): String? {
        var inS = false
        var inD = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (inS) { if (c == '\'') inS = false }
            else {
                if (c == '\\' && i + 1 < cmd.length) { i += 2; continue }
                if (c == '"') inD = !inD
                else if (c == '\'' && !inD) inS = true
                else if (c == '`') return "backtick command substitution"
                else if (c == '$' && i + 1 < cmd.length && (cmd[i + 1] == '(' || cmd[i + 1] == '{')) return "command/parameter substitution"
                else if (c == '\n' || c == '\r') return "multi-line command"
                else if (!inD && (c == '<' || c == '>')) {
                    // The scan reaches ">" after the fd digit: "2>&1" is seen here as ">&1" (missed until 2026-10-16).
                    // The token must END there: bash reads ">&1x" / ">&2evil" as "write stdout+stderr to the FILE 1x",
                    // and ">/dev/nullx" as a file too (security review, 2026-10-16).
                    val rest = cmd.substring(i)
                    val ok = Regex("""^(2>&1|>&1|>&2|1>&2|2>\s*/dev/null|>\s*/dev/null|1>\s*/dev/null|&>\s*/dev/null)(?=$|[\s|;&)])""").find(rest)
                    if (ok != null) { i += ok.value.length; continue }
                    return "redirection"
                }
                else if (!inD && c == '&' && !(i + 1 < cmd.length && cmd[i + 1] == '&') && !(i > 0 && cmd[i - 1] == '&') && !(i > 0 && (cmd[i - 1] == '>' || cmd[i - 1] == '<'))) return "background operator"
            }
            i++
        }
        return null
    }

    fun classify(raw: String): Classification {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Classification(Risk.FORBIDDEN, "empty command")
        if (cmd.length > 4000) return Classification(Risk.FORBIDDEN, "command too long")
        for ((re, why) in forbidden) if (re.containsMatchIn(cmd)) return Classification(Risk.FORBIDDEN, why)
        efibootmgrChange(cmd)?.let { return Classification(Risk.FORBIDDEN, it) }
        if (destructive.containsMatchIn(cmd)) return Classification(Risk.DESTRUCTIVE, "can delete or irreversibly remove data/packages")

        return notReadOnly(cmd)?.let { Classification(Risk.MODIFYING, it) } ?: Classification(Risk.READ_ONLY, "allowlisted read-only commands")
    }

    /** Why [cmd] isn't provably read-only, or null if it is. */
    private fun notReadOnly(cmd: String): String? {
        hasUnsafeSyntax(cmd)?.let { return "uses $it" }
        val segments = splitSegments(cmd) ?: return "unbalanced quotes"
        if (segments.isEmpty()) return "no command"

        for (seg in segments) {
            var argv = seg
            // allow a leading `sudo` / `env VAR=` for classification of the underlying program
            if (argv.firstOrNull() == "sudo") argv = argv.drop(1).dropWhile { it.startsWith("-") }
            val prog = argv.firstOrNull()?.substringAfterLast('/') ?: return "empty segment"
            val args = argv.drop(1)
            if (prog !in readOnlyPrograms) return "'$prog' is not on the read-only allowlist"
            if (prog.endsWith("-ro")) return "'$prog' not supported"
            checkProgram(prog, args)?.let { return it }
            if (sensitivePath.containsMatchIn(args.joinToString(" "))) return "touches a credential/secret path"
        }
        return null
    }

    /**
     * efibootmgr may only LIST boot entries: a command that mentions it anywhere (also split by quotes or backslashes,
     * like `efi"boot"mgr`) is refused unless the whole command is provably read-only. Two security reviews on 2026-10-29
     * found shell syntax a word-by-word check of its options can't follow (`$'-B'`, `-{B,v}`, `-?`, a lone `&`, line
     * continuations), so changes to boot entries are never run, even approved; the user does them by hand.
     */
    private fun efibootmgrChange(cmd: String): String? {
        if ("efibootmgr" !in cmd.replace(Regex("""['"\\]"""), "")) return null
        return notReadOnly(cmd)?.let { "efibootmgr may only list boot entries here (changes are done by hand)" }
    }

    private fun checkProgram(prog: String, args: List<String>): String? {
        val positional = args.filter { !it.startsWith("-") }
        return when (prog) {
            "systemctl" -> {
                val flags = args.filter { it.startsWith("-") }
                val sub = positional.firstOrNull()
                if (sub != null && sub !in systemctlReadOnly) "systemctl $sub changes state"
                else if (sub == null && flags.none { it in systemctlReadOnly }) "systemctl without a read-only subcommand"
                else null
            }
            "journalctl" -> args.firstOrNull { a -> journalctlDenied.any { a.startsWith(it) } }?.let { "journalctl $it modifies the journal" }
            "dmesg" -> args.firstOrNull { it == "-c" || it == "-C" || it == "--clear" || it == "--read-clear" || it.startsWith("-n") || it == "-D" || it == "-E" || it == "--console-off" || it == "--console-on" || it == "--console-level" }?.let { "dmesg $it changes kernel state" }
            "ip" -> {
                val verb = positional.firstOrNull { it !in ipReadOnly }
                if (verb != null && verb in ipDeniedVerbs) "ip $verb changes network state"
                else if (positional.drop(1).any { it in ipDeniedVerbs }) "ip modifies network state"
                else if (positional.firstOrNull() == "netns" || positional.firstOrNull() == "tuntap") "ip ${positional.first()} not read-only" else null
            }
            "find" -> args.firstOrNull { it in setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls") }?.let { "find $it can modify files" }
            "mount" -> if (positional.isNotEmpty() || args.any { it.startsWith("-o") || it == "-a" }) "mount with arguments mounts filesystems" else null
            "sysctl" -> if (args.any { it.contains('=') || it == "-w" || it == "-p" || it == "--system" || it == "--load" }) "sysctl write" else null
            "apt" -> if (positional.firstOrNull() in (pkgReadOnly["apt"] ?: emptySet())) null else "apt ${positional.firstOrNull() ?: ""} is not read-only"
            in pkgReadOnly.keys -> {
                val allowed = pkgReadOnly.getValue(prog)
                val ok = if (prog in setOf("dpkg", "rpm", "pacman", "iptables")) args.isNotEmpty() && args.all { it in allowed || !it.startsWith("-") } && args.any { it in allowed || (prog == "dpkg" && it.startsWith("-l")) }
                else positional.firstOrNull()?.let { it in allowed } ?: false
                if (ok) null else "$prog ${args.joinToString(" ")} is not on the read-only subcommand list"
            }
            "systemd-analyze" -> if (positional.firstOrNull() in setOf("blame", "critical-chain", "time", "verify", "security", "plot", "unit-paths")) null else "systemd-analyze subcommand not read-only"
            "ping" -> if (args.none { it == "-c" }) "ping must use -c" else null
            "lsof", "ss", "netstat" -> args.firstOrNull { it == "-K" || it == "--kill" }?.let { "$prog $it kills sockets" }
            "smartctl" -> if (args.any { it in setOf("-t", "--test", "-s", "--smart=on", "--smart=off", "-X", "--abort", "-S") }) "smartctl starts tests/changes settings" else null
            "nft" -> if (positional.firstOrNull() == "list") null else "nft not read-only"
            "date" -> if (args.any { it == "-s" || it == "--set" || it.startsWith("--set=") }) "date -s sets the clock" else null
            "hostname" -> if (positional.isNotEmpty()) "hostname with an argument sets the name" else null
            "sort" -> if (args.any { it == "-o" || it.startsWith("--output") }) "sort -o writes a file" else null
            "tail", "head" -> null
            "xrandr" -> if (args.any { it == "--output" || it == "--mode" || it == "--auto" || it == "--off" || it == "--newmode" || it == "--addmode" }) "xrandr changes the display" else null
            "command", "type" -> if (args.firstOrNull() in setOf("-v", "-V", "-p")) null else "only 'command -v' allowed"
            "vulkaninfo", "glxinfo" -> null
            // Only the listing; every other option selects, activates or changes boot entries (2026-10-29).
            // Redirection words are left by the splitter; hasUnsafeSyntax already allowed only 2>&1 and /dev/null ones.
            "efibootmgr" -> if (args.filterNot { Regex("""^[0-9&]?>(&[0-9]|/dev/null)?$""").matches(it) || it == "/dev/null" }
                    .all { it in setOf("-v", "-vv", "--verbose", "-V", "--version") }) null else "efibootmgr ${args.joinToString(" ")} changes boot entries"
            else -> null
        }
    }

    /** Wraps a user-visible command for privileged execution without shell injection from a password. */
    fun sudoWrap(cmd: String): String = "sudo -S -p '' sh -c " + shellQuote(cmd)

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
