package com.maodouchat.util

/**
 * Display-only HTML entity unescape for chat bodies.
 * Some peers store literal `&#10;` / `&amp;` in plaintext; the bubble must not show them raw.
 * Do not use on wire ciphertext or persistence merge.
 */
object ChatTextDisplayPolicy {
    fun unescapeHtmlEntities(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
        var out = raw
        out = NUMERIC_DECIMAL.replace(out) { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        out = NUMERIC_HEX.replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private val NUMERIC_DECIMAL = Regex("&#(\\d{1,7});")
    private val NUMERIC_HEX = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)
}
