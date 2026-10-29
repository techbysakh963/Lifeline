package com.rodinrescue.security

/**
 * Rules for commands keyboard rescue types into a Windows Command Prompt (cmd.exe), usually the one in the Windows
 * Recovery Environment (Troubleshoot > Advanced options > Command Prompt). It runs as SYSTEM, so approval is the gate.
 *
 * Same principle as [CommandPolicy]: READ_ONLY only for known read-only programs, without redirection or chaining;
 * anything else asks. The risky patterns live in [WinRecoveryRules]. Case-insensitive, like cmd.exe.
 *
 * The AI only *says* which shell is on screen, so any "-" option makes a command non-read-only here: Windows tools use
 * "/" options, and this keeps a Linux command from passing as a Windows read.
 */
object WinRecoveryPolicy {
    private val readOnlyPrograms = setOf(
        "dir", "type", "ver", "vol", "echo", "cd", "chdir", "tree", "find", "findstr", "where", "set", "path",
        "bcdedit", "bootrec", "reagentc", "chkdsk", "mountvol", "attrib", "wmic", "manage-bde", "dism", "sfc", "ipconfig", "whoami", "hostname",
        "list", "detail", "exit"   // diskpart: looking only
    )

    /** Why [prog] with [args] isn't a pure read, or null. Options are lower-cased. */
    private fun check(prog: String, args: List<String>): String? {
        val opts = args.filter { it.startsWith("/") }
        val pos = args.filter { !it.startsWith("/") }
        return when (prog) {
            "set", "path" -> if (args.isEmpty()) null else "$prog with arguments changes a setting"
            "bcdedit" -> if (opts.all { it == "/enum" || it == "/v" }) null else "bcdedit $opts changes the boot configuration"
            "bootrec" -> if (opts == listOf("/scanos")) null else "bootrec ${opts.joinToString(" ")} changes the boot records"
            "reagentc" -> if (opts == listOf("/info")) null else "reagentc changes recovery settings"
            "chkdsk" -> if (opts.all { it == "/v" || it == "/i" || it == "/c" }) null else "chkdsk ${opts.joinToString(" ")} repairs the disk"
            "mountvol" -> if (args.isEmpty()) null else "mountvol with arguments changes drive letters"
            "attrib" -> if (args.none { it.startsWith("+") || it.startsWith("-") }) null else "attrib changes file attributes"
            "wmic" -> if (pos.any { it == "get" } && pos.none { it in setOf("call", "set", "delete", "create") }) null else "wmic without get"
            "manage-bde" -> if (args.all { it == "-status" || Regex("^[a-z]:$").matches(it) }) null else "manage-bde options can change encryption"
            "dism" -> if (dismRead(opts)) null else "dism ${opts.joinToString(" ")} changes Windows"
            "sfc" -> if (opts.isNotEmpty() && opts.all { it == "/verifyonly" || it.startsWith("/offbootdir=") || it.startsWith("/offwindir=") || it.startsWith("/verifyfile=") }
                    && opts.any { it == "/verifyonly" || it.startsWith("/verifyfile=") }) null else "sfc repairs files"
            "hostname" -> if (args.isEmpty()) null else "hostname with arguments"
            "ipconfig" -> if (opts.all { it == "/all" }) null else "ipconfig ${opts.joinToString(" ")} changes the network"
            else -> null
        }
    }

    private val dismOk = setOf("/online", "/english", "/cleanup-image", "/checkhealth", "/scanhealth")
    /** dism on an offline image (/image:D:\) or /online, only /get-… queries or the health checks. */
    private fun dismRead(opts: List<String>): Boolean {
        if (opts.none { it.startsWith("/image:") || it == "/online" }) return false
        if (!opts.all { it in dismOk || it.startsWith("/image:") || it.startsWith("/get-") || it.startsWith("/format:") }) return false
        return opts.none { it == "/cleanup-image" } || opts.any { it == "/checkhealth" || it == "/scanhealth" }
    }

    /** Split on cmd.exe separators (& && || |); null when quotes don't balance. */
    private fun segments(cmd: String): List<List<String>>? {
        if (cmd.count { it == '"' } % 2 != 0) return null
        val out = mutableListOf<List<String>>()
        var inQ = false; val cur = StringBuilder()
        fun flush() { val w = Regex("\"[^\"]*\"|\\S+").findAll(cur.toString()).map { it.value.trim('"') }.toList(); if (w.isNotEmpty()) out += w; cur.clear() }
        for (c in cmd) { if (c == '"') inQ = !inQ; if (!inQ && (c == '&' || c == '|')) flush() else cur.append(c) }
        flush(); return out
    }

    fun classify(raw: String): Classification {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Classification(Risk.FORBIDDEN, "empty command")
        if (cmd.length > 1000) return Classification(Risk.FORBIDDEN, "command too long")
        WinRecoveryRules.risky(cmd)?.let { return it }
        if (Regex("[<>^%!]").containsMatchIn(cmd)) return Classification(Risk.MODIFYING, "uses redirection, escapes or variables")
        if ('\n' in cmd || '\r' in cmd) return Classification(Risk.MODIFYING, "multi-line command")
        val segs = segments(cmd) ?: return Classification(Risk.MODIFYING, "unbalanced quotes")
        for (seg in segs) {
            val prog = seg.first().lowercase().removeSuffix(".exe")
            val args = seg.drop(1).map { it.lowercase() }
            // a bare drive change ("D:") only moves to that drive
            if (seg.size == 1 && Regex("^[a-z]:$").matches(prog)) continue
            if ('\\' in prog || '/' in prog) return Classification(Risk.MODIFYING, "runs a program by path")
            if (prog !in readOnlyPrograms) return Classification(Risk.MODIFYING, "'$prog' is not on the read-only list")
            if (prog != "manage-bde" && prog != "attrib" && args.any { it.startsWith("-") }) return Classification(Risk.MODIFYING, "'-' options are not Windows read options")
            check(prog, args)?.let { return Classification(Risk.MODIFYING, it) }
        }
        return Classification(Risk.READ_ONLY, "read-only Windows commands")
    }
}
