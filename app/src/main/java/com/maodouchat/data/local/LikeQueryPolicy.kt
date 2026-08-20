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

    /**
     * 9.236：转义前缀片段（如服务端返回的 accountId）供 `LIKE prefix || '%' ESCAPE '\'` 使用。
     * 与 [escapeForContains] 不同：空白也是合法前缀内容，不得归空。
     * 写入侧若拼接同一前缀必须用同样转义，保证键字面量与查询匹配一致。
     */
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
