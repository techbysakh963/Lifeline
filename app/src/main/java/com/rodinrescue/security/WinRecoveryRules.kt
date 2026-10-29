package com.rodinrescue.security

/** The FORBIDDEN and DESTRUCTIVE patterns for [WinRecoveryPolicy] (Windows Command Prompt / recovery). */
internal object WinRecoveryRules {
    private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    /** Never typed. */
    private val never = listOf(
        re("""(^|[\s&|])format(\.com)?\s+"?[a-z]:""") to "formatting a drive",
        re("""^\s*(clean(\s+all)?|convert\s+\w+|delete\s+(partition|volume|disk)\b.*|create\s+(partition|volume|vdisk)\b.*|extend\b.*|shrink\b.*|format\b.*)$""") to "changing partitions (diskpart)",
        re("""\bdiskpart\b.*\s/s\s""") to "running a diskpart script",
        re("""\bcipher\b.*\s/w""") to "wiping disk space",
        re("""\b(vssadmin|wmic)\b.*\bshadow""") to "changing restore points",
        re("""\bwbadmin\b.*\bdelete\b""") to "deleting backups",
        re("""\bmanage-bde\b.*\s-(off|disable|forcerecovery)\b|\bmanage-bde\b.*-protectors\s+-(delete|disable)\b""") to "turning off disk encryption",
        re("""\bbcdedit\b.*\b(testsigning|nointegritychecks)\b""") to "turning off driver signing",
        re("""\bnet\s+user\s+\S+\s+[^/\s]""") to "changing a password (do it yourself)",
        re("""\b(rd|rmdir|del|erase)\b.*\s/s\b.*\s"?[a-z]:\\?(windows|users|program files|programdata)?\\?\*?"?\s*$""") to "deleting a whole drive or system folder",
        re("""\breg\s+delete\s+"?(hklm|hkey_local_machine|hkcu|hkey_current_user|hku|hkey_users)(\\\w+)?\\?"?(\s+/f)?\s*$""") to "deleting a registry hive"
    )

    /** Allowed only with the typed confirmation: deletions, and boot repair (the real fix in recovery, but can stop Windows starting). */
    private val confirm = listOf(
        re("""\bbootrec\b.*/(fixmbr|fixboot|rebuildbcd)\b"""),
        re("""\bbcdboot\b"""),
        re("""\bbcdedit\b.*\s/(set|deletevalue|delete|create|copy|default|displayorder|bootsequence|import|store)\b"""),
        re("""(^|[\s&|])(del|erase|rd|rmdir)(\s|$)"""),
        re("""\breg\s+delete\b|\bdism\b.*/(remove-\w+|disable-feature|resetbase)\b|\bpnputil\b.*/delete-driver\b"""),
        re("""^\s*(active|inactive|gpt\s+attributes.*|set\s+id.*)$""")
    )

    fun risky(cmd: String): Classification? {
        never.firstOrNull { it.first.containsMatchIn(cmd) }?.let { return Classification(Risk.FORBIDDEN, it.second) }
        if (confirm.any { it.containsMatchIn(cmd) }) return Classification(Risk.DESTRUCTIVE, "deletes data or changes how Windows starts")
        return null
    }
}
