package com.maodouchat.data.local

/**
 * Escape user input for SQL LIKE patterns that use ESCAPE '\'.
 * Without this, typing `%` / `_` / `\` turns the list search into a wildcard fan-out.
 */
object LikeQueryPolicy {

    const val ESCAPE_CHAR: Char = '\\'

    /**
     * Escape `\`, `%`, and `_` for use inside a `LIKE '%…%' ESCAPE '\'` clause.
     * Returns blank when [raw] is blank after trim.
     */
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
}
