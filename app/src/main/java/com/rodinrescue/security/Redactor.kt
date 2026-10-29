package com.rodinrescue.security

/** Removes secrets from text before it is sent to an AI provider or stored in the audit log. */
object Redactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to "[REDACTED PRIVATE KEY]",
        Regex("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization)\\b(\\s*[=:]\\s*)(\\S+)") to "$1$2[REDACTED]",
        Regex("\\bsk-[A-Za-z0-9_-]{16,}") to "[REDACTED KEY]",
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}") to "Bearer [REDACTED]",
        Regex("\\b[a-z0-9]{2,32}:\\$[0-9a-z]{1,2}\\$[^:\\s]+") to "[REDACTED SHADOW HASH]"
    )

    fun redact(text: String, extraSecrets: Collection<String> = emptyList()): String {
        var out = text
        for (s in extraSecrets) if (s.length >= 4) out = out.replace(s, "[REDACTED]")
        for ((re, rep) in rules) out = re.replace(out, rep)
        return out
    }
}
