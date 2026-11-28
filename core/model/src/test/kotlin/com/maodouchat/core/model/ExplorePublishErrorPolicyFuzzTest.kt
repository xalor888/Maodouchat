package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for ExplorePublishErrorPolicy (checklist line
// 931, 53rd block). Test-only, zero production changes. Pinned quirks: a
// 401 beats restriction copy, but lookalike auth copy without a 401 falls
// through to FALLBACK; post restriction beats message restriction beats
// suspended; displayMessage keeps the trimmed server copy regardless of kind.
class ExplorePublishErrorPolicyFuzzTest {

// ---------- B2 oracles: ExplorePublishErrorPolicy ----------

private fun isPostRestrictedOracle(message: String): Boolean =
    message.contains("限制发布动态") ||
        message.contains("限制发动态") ||
        message.contains("禁动态") ||
        message.contains("post restricted") ||
        message.contains("restricted from posting")

private fun isMessageRestrictedOracle(message: String): Boolean =
    message.contains("限制发消息") ||
        message.contains("禁言") ||
        message.contains("message restricted")

private fun isSuspendedOracle(message: String): Boolean =
    message.contains("临时封禁") ||
        message.contains("账号已被") ||
        message.contains("suspended") ||
        message.contains("banned")

private fun classifyOracle(error: Throwable): ExplorePublishErrorKind {
    val api = error as? ApiException
    val status = api?.statusCode
    // ApiException carries serverMessage as its own message, so an Api
    // error's text is the server copy twice ("S S"); a plain exception
    // contributes only its own message.
    val message = listOfNotNull(api?.serverMessage, error.message)
        .joinToString(" ")
        .lowercase()

    if (status == 401 || message.contains("请先登录") || message.contains("unauthorized") ||
        message.contains("sign in") || message.contains("token")
    ) {
        // Prefer auth only for a real 401: lookalike copy without a 401
        // falls through to the restriction checks below.
        if (status == 401) return ExplorePublishErrorKind.AUTH
    }
    if (isPostRestrictedOracle(message) || (status == 403 && message.contains("动态"))) {
        return ExplorePublishErrorKind.POST_RESTRICTED
    }
    if (isMessageRestrictedOracle(message)) {
        return ExplorePublishErrorKind.MESSAGE_RESTRICTED
    }
    if (isSuspendedOracle(message) || (status == 403 && message.contains("封禁"))) {
        return ExplorePublishErrorKind.SUSPENDED
    }
    if (status == 401) return ExplorePublishErrorKind.AUTH
    return ExplorePublishErrorKind.FALLBACK
}

private fun displayMessageOracle(error: Throwable, fallback: String): String {
    val server = (error as? ApiException)?.serverMessage?.trim().orEmpty()
        .ifBlank { error.message?.trim().orEmpty() }
    return server.ifBlank { fallback }
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
    fun classifyRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val error = randomError(rnd)
            assertEquals(classifyOracle(error), ExplorePublishErrorPolicy.classify(error))
        }
    }

    @Test
    fun displayMessageRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val error = randomError(rnd)
            val fallback = randomString(rnd)
            assertEquals(
                displayMessageOracle(error, fallback),
                ExplorePublishErrorPolicy.displayMessage(error, fallback)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        fun api(status: Int?, server: String?, kind: ApiFailureKind = ApiFailureKind.HTTP) =
            ApiException(kind = kind, statusCode = status, serverMessage = server)

        // A real 401 beats restriction copy; lookalike copy without a 401
        // falls through to FALLBACK.
        assertEquals(ExplorePublishErrorKind.AUTH, ExplorePublishErrorPolicy.classify(api(401, "你已被限制发布动态")))
        assertEquals(ExplorePublishErrorKind.AUTH, ExplorePublishErrorPolicy.classify(api(401, null)))
        assertEquals(ExplorePublishErrorKind.FALLBACK, ExplorePublishErrorPolicy.classify(api(500, "token 已过期")))
        assertEquals(ExplorePublishErrorKind.FALLBACK, ExplorePublishErrorPolicy.classify(api(500, "TOKEN")))
        assertEquals(ExplorePublishErrorKind.FALLBACK, ExplorePublishErrorPolicy.classify(api(403, "sign in to continue")))
        assertEquals(ExplorePublishErrorKind.FALLBACK, ExplorePublishErrorPolicy.classify(RuntimeException("请先登录")))
        assertEquals("请先登录", ExplorePublishErrorPolicy.displayMessage(RuntimeException("请先登录"), "发布失败"))

        // Restriction branches, in priority order: post > message > suspended.
        assertEquals(ExplorePublishErrorKind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(api(403, "你已被限制发布动态，解除时间 2026-08-01")))
        assertEquals(ExplorePublishErrorKind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(api(403, "动态发布暂时不可用")))
        assertEquals(ExplorePublishErrorKind.POST_RESTRICTED, ExplorePublishErrorPolicy.classify(api(403, "You are restricted from posting for 7 days")))
        assertEquals(ExplorePublishErrorKind.MESSAGE_RESTRICTED, ExplorePublishErrorPolicy.classify(api(403, "限制发消息，账号有临时封禁记录")))
        assertEquals(ExplorePublishErrorKind.MESSAGE_RESTRICTED, ExplorePublishErrorPolicy.classify(api(403, "你已被禁言")))
        assertEquals(ExplorePublishErrorKind.SUSPENDED, ExplorePublishErrorPolicy.classify(api(403, "账号已被临时封禁")))
        assertEquals(ExplorePublishErrorKind.SUSPENDED, ExplorePublishErrorPolicy.classify(api(403, "账号封禁中")))
        assertEquals(ExplorePublishErrorKind.AUTH, ExplorePublishErrorPolicy.classify(api(401, "Session expired, please sign in again")))
        assertEquals(ExplorePublishErrorKind.FALLBACK, ExplorePublishErrorPolicy.classify(api(500, null)))

        // displayMessage keeps the trimmed server copy, else the fallback.
        assertEquals(
            "你已被限制发布动态，解除时间 2026-08-01",
            ExplorePublishErrorPolicy.displayMessage(api(403, "你已被限制发布动态，解除时间 2026-08-01"), "发布失败")
        )
        assertEquals(
            "账号已被临时封禁，解封时间 2026-09-01",
            ExplorePublishErrorPolicy.displayMessage(api(403, "账号已被临时封禁，解封时间 2026-09-01"), "发布失败")
        )
        assertEquals(
            "Session expired, please sign in again",
            ExplorePublishErrorPolicy.displayMessage(api(401, "Session expired, please sign in again"), "发布失败")
        )
        assertEquals("发布失败", ExplorePublishErrorPolicy.displayMessage(api(500, null), "发布失败"))
        assertEquals("发布失败", ExplorePublishErrorPolicy.displayMessage(api(500, "   "), "发布失败"))
        assertEquals(
            "你已被限制发布动态",
            ExplorePublishErrorPolicy.displayMessage(api(403, "  你已被限制发布动态  "), "发布失败")
        )
    }
}
