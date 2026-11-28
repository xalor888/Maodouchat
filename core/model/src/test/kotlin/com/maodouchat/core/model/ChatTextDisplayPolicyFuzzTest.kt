package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for ChatTextDisplayPolicy (checklist line 931,
// 53rd block). Test-only, zero production changes. The oracle reimplements
// the entity unescape by hand; production is called directly, so any drift
// shows up as a mismatch. Pinned quirks: numeric entities run before named
// ones ("&#38;lt;" -> "<" but "&amp;lt;" -> "&lt;"), "&#38;#x41;" -> "A"
// while "&#38;#65;" stays literal, surrogate code points decode to a lone
// surrogate ("&#55296;" -> "\uD800"), "&amp;amp;" -> "&amp;" (one pass).
class ChatTextDisplayPolicyFuzzTest {

// ---------- B1 oracle: ChatTextDisplayPolicy ----------

private val NUMERIC_DECIMAL_ORACLE = Regex("&#(\\d{1,7});")
private val NUMERIC_HEX_ORACLE = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

private fun unescapeHtmlEntitiesOracle(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = NUMERIC_DECIMAL_ORACLE.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = NUMERIC_HEX_ORACLE.replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    // Named entities, in production order, one single pass each: a "&amp;"
    // decoded here leaves a literal "&lt;" behind (nothing re-scans it).
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

// ---------- generators (Random(42) in the CI tests) ----------

private val STR_POOL = listOf(
    "", " ", "  ", "   a   ", "a", "ab", "abc", "|", "^", "\u0001", "\u0002",
    "a|b", "a^b", "a\u0001b", "a\u0002b", "||", "^^", "a|b^c",
    "hello", "hello world", "  padded  ", "\t", "\n", "a\tb", "a\nb",
    "é", "ß", "İ", "i", "I", "中文测试", "😀", "a😀b", "😀😀😀",
    "\u00a0", "x\u00a0y", "\u2007", "\u202f",
    "rock", "paper", "scissors", "ROCK", "Heads", "HEADS", "heads", "TAILS",
    "0", "1", "-1", "42", "9999999999", "2147483647", "-2147483648",
    "a".repeat(50), "x".repeat(130), "word ".repeat(30),
    "PUBLIC", "CONTACTS", "PRIVATE", "FRIENDS", "public", "private"
)

private val CH_POOL = listOf(
    "a", "b", "z", "A", "Z", "0", "9", " ", "|", "^", "\u0001", "\u0002",
    "\t", "\n", "é", "ß", "中", "😀", "\u00a0", ".", "-", "_", "!", "&",
    ";", "#", "%", "+", "@", "/", "?", "'", "\""
)

private fun randomString(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0 -> STR_POOL[rnd.nextInt(STR_POOL.size)]
        1 -> buildString {
            repeat(rnd.nextInt(0, 30)) { append(CH_POOL[rnd.nextInt(CH_POOL.size)]) }
        }
        2 -> {
            val n = listOf(
                0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 15, 16, 17, 19, 20, 21,
                23, 24, 25, 29, 30, 31, 39, 40, 41, 49, 50, 51, 59, 60, 61,
                79, 80, 81, 99, 100, 101, 119, 120, 121, 130, 159, 160, 161
            )[rnd.nextInt(45)]
            val ch = listOf("a", " ", "|", "😀")[rnd.nextInt(4)]
            ch.repeat(n)
        }
        else -> {
            val base = buildString {
                repeat(rnd.nextInt(0, 25)) { append('a' + rnd.nextInt(26)) }
            }
            base + listOf("", " ", "|", "^", "\u0001", "😀", "  ")[rnd.nextInt(7)]
        }
    }
}

private val ENTITY_POOL = listOf(
    "&#65;", "&#x41;", "&#X42;", "&#0;", "&#1114111;", "&#1114112;",
    "&#55296;", "&#57343;", "&#55295;", "&#57344;", "&#9;", "&#13;", "&#34;",
    "&#127;", "&#128512;", "&#065;", "&#x0041;", "&#0000065;", "&#x4a;",
    "&#X6A;", "&#12345678;", "&#x1234567;", "&#;", "&#x;", "&#65",
    "&amp;", "&lt;", "&gt;", "&nbsp;", "&quot;", "&apos;", "&#39;",
    "&copy;", "&unknown;", "&AMP;", "&LT;", "&NBSP;", "&amp",
    "&amp;lt;", "&amp;#65;", "&amp;#x41;", "&amp;amp;", "&lt;&lt;",
    "&#38;", "&#38;lt;", "&#38;#65;", "&#38;#x41;", "&#38;amp;",
    "a", " ", "&", ";", "#", "65", "x41", "hello", "1 < 2"
)

private fun randomEntityString(rnd: Random): String {
    return when (rnd.nextInt(3)) {
        0 -> randomString(rnd)
        1 -> buildString {
            repeat(rnd.nextInt(1, 6)) { append(ENTITY_POOL[rnd.nextInt(ENTITY_POOL.size)]) }
        }
        else -> {
            val base = randomString(rnd)
            val frag = ENTITY_POOL[rnd.nextInt(ENTITY_POOL.size)]
            when (rnd.nextInt(3)) {
                0 -> frag + base
                1 -> base + frag
                else -> base + frag + randomString(rnd)
            }
        }
    }
}

private val ERROR_WORD_POOL = listOf(
    "请先登录", "unauthorized", "Unauthorized", "UNAUTHORIZED", "token",
    "TOKEN", "Token expired", "token 已过期", "sign in", "Sign In",
    "please sign in", "Session expired, please sign in again",
    "限制发布动态", "限制发动态", "禁动态", "post restricted",
    "restricted from posting", "RESTRICTED FROM POSTING",
    "You are restricted from posting for 7 days",
    "你已被限制发布动态，解除时间 2026-08-01",
    "限制发消息", "禁言", "message restricted", "你已被禁言",
    "限制发消息，账号有临时封禁记录",
    "临时封禁", "账号已被", "suspended", "banned", "账号已被临时封禁",
    "账号封禁中", "动态", "封禁", "动态发布暂时不可用",
    "csrf token invalid", "ok", "", "  ", "boom", "  boom  ",
    "İ", "ß", "限制", "发布", "消息", "账号"
)

private fun randomErrorMessage(rnd: Random): String {
    return when (rnd.nextInt(3)) {
        0 -> ERROR_WORD_POOL[rnd.nextInt(ERROR_WORD_POOL.size)]
        1 -> randomString(rnd)
        else -> {
            val a = ERROR_WORD_POOL[rnd.nextInt(ERROR_WORD_POOL.size)]
            val b = ERROR_WORD_POOL[rnd.nextInt(ERROR_WORD_POOL.size)]
            listOf(a, "$a $b", "$a，$b", "$a$b")[rnd.nextInt(4)]
        }
    }
}

private fun randomStatus(rnd: Random): Int {
    return when (rnd.nextInt(4)) {
        0 -> listOf(
            200, 400, 401, 403, 404, 410, 500, 502, 0, -1,
            Int.MAX_VALUE, Int.MIN_VALUE
        )[rnd.nextInt(12)]
        1 -> rnd.nextInt(400, 500)
        2 -> rnd.nextInt(0, 600)
        else -> rnd.nextInt()
    }
}

private fun randomKind(rnd: Random): ApiFailureKind =
    ApiFailureKind.entries[rnd.nextInt(ApiFailureKind.entries.size)]

private fun randomError(rnd: Random): Throwable {
    return if (rnd.nextInt(10) < 7) {
        val status = if (rnd.nextInt(4) == 0) null else randomStatus(rnd)
        val server = if (rnd.nextInt(4) == 0) null else randomErrorMessage(rnd)
        ApiException(kind = randomKind(rnd), statusCode = status, serverMessage = server)
    } else {
        val msg = if (rnd.nextInt(5) == 0) null else randomErrorMessage(rnd)
        RuntimeException(msg)
    }
}

private val USERNAME_POOL = listOf(
    "", " ", "   ", "\t", "\u00a0", "@", "@@@", "ada/", "/ada", "u/ada",
    "%40ada", "%2540ada", "%zzada", "%2Fada", "%20ada%20", "a+b", "%FF",
    "%C3%A9", "%C3%28", "%2B", "%", "%2", "ada#x?y=1", "'@ada'", "\"ada\"",
    "  @ada  ", "https://chat.mdou.me/u/ada?x=1", "ada", "u/ada?x=1#frag",
    "ada#section?x=1", "'ada\"", "\"'ada'\"", "u%2Fada", "@ ada", "@@ada"
)

private fun randomUsername(rnd: Random): String? {
    return when (rnd.nextInt(6)) {
        0 -> null
        1 -> USERNAME_POOL[rnd.nextInt(USERNAME_POOL.size)]
        2 -> randomEntityString(rnd)
        else -> randomString(rnd)
    }
}

    @Test
    fun unescapeRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val raw = randomEntityString(rnd)
            assertEquals(unescapeHtmlEntitiesOracle(raw), ChatTextDisplayPolicy.unescapeHtmlEntities(raw))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        val cases = listOf(
            "" to "",
            "&" to "&",
            "a & b" to "a & b",
            "hello_live_sweep" to "hello_live_sweep",
            "&#65;&#x42;&#67;" to "ABC",
            "&#x41;&#X42;" to "AB",
            "&#X4a;&#x4B;" to "JK",
            "&#X6A;" to "j",
            "&#0;" to "&#0;",
            "&#1114111;" to "\uDBFF\uDFFF",
            "&#1114112;" to "&#1114112;",
            "&#x10FFFF;" to "\uDBFF\uDFFF",
            "&#x110000;" to "&#x110000;",
            "&#65" to "&#65",
            "&#;" to "&#;",
            "&#x;" to "&#x;",
            "&amp" to "&amp",
            "&#12345678;" to "&#12345678;",
            "&#x1234567;" to "&#x1234567;",
            "&#127;" to "\u007F",
            "&#9;" to "\t",
            "&#13;" to "\r",
            "&#34;" to "\"",
            "&#128512;" to "😀",
            "&#065;" to "A",
            "&#x0041;" to "A",
            "&#0000065;" to "A",
            "&AMP;" to "&AMP;",
            "&LT;" to "&LT;",
            "&NBSP;" to "&NBSP;",
            "&copy;&unknown;" to "&copy;&unknown;",
            "&nbsp;&quot;&#39;&apos;&lt;&gt;" to " \"''<>",
            "1 &lt; 2 &amp;&amp; 3 &gt; 2" to "1 < 2 && 3 > 2",
            "&#38;" to "&",
            "&#38;lt;" to "<",
            "&#38;#65;" to "&#65;",
            "&#38;#x41;" to "A",
            "&#38;amp;" to "&",
            "&amp;lt;" to "&lt;",
            "&amp;#65;" to "&#65;",
            "&amp;#x41;" to "&#x41;",
            "&amp;amp;" to "&amp;",
            // Surrogate code points decode to a lone surrogate, no throw.
            "&#55296;" to "\uD800",
            "&#57343;" to "\uDFFF",
            "&#55295;" to "\uD7FF",
            "&#57344;" to "\uE000"
        )
        for ((raw, want) in cases) {
            assertEquals(want, ChatTextDisplayPolicy.unescapeHtmlEntities(raw), "unescape($raw)")
        }
    }
}
