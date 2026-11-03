package com.maodouchat.core.model

// LIKE-pattern escaping for queries using ESCAPE '\': without it, `%` / `_` / `\` in input would widen the match.
object LikeQueryPolicy {

    const val ESCAPE_CHAR: Char = '\\'

    // Blank input stays blank; every `\`, `%`, `_` gets a `\` prefix.
    fun escapeForContains(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length + 4)
        for (ch in raw) {
            when (ch) {
                ESCAPE_CHAR, '%', '_' -> {
                    sb.append(ESCAPE_CHAR)
                    sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // Same escaping for a LIKE prefix, except blank input is kept: a blank prefix is legal key content.
    fun escapeForPrefix(raw: String): String {
        val sb = StringBuilder(raw.length + 4)
        for (ch in raw) {
            when (ch) {
                ESCAPE_CHAR, '%', '_' -> {
                    sb.append(ESCAPE_CHAR)
                    sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
