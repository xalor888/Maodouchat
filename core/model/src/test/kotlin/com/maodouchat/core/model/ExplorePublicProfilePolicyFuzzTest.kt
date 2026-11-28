package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fuzz / random-oracle tests for ExplorePublicProfilePolicy (checklist line
// 931, 53rd block). Test-only, zero production changes. Pinned quirks:
// percent-decoding runs exactly once ("%2540ada" -> "%40ada", "%zzada"
// untouched), "+" decodes to a space, malformed UTF-8 becomes U+FFFD,
// 404/410 beat the NETWORK kind in displayLoadError.
class ExplorePublicProfilePolicyFuzzTest {

// ---------- B3 oracles: ExplorePublicProfilePolicy ----------

private fun decodeOnceOracle(value: String): String {
    return try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}

private fun normalizeUsernameOracle(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var value = raw.trim()
    value = decodeOnceOracle(value).trim()
    value = value.trim('"', '\'')
    while (value.startsWith("@")) {
        value = value.drop(1).trim()
    }
    val slash = value.lastIndexOf('/')
    if (slash >= 0) {
        value = value.substring(slash + 1)
    }
    value = value.substringBefore('#').substringBefore('?').trim()
    return value
}

private fun isValidUsernameOracle(username: String): Boolean = username.isNotBlank()

private fun displayLoadErrorOracle(
    failure: Throwable,
    notFound: String,
    loadFailed: String,
    networkError: String
): String {
    val api = failure as? ApiException
    val status = api?.statusCode
    if (status == 404 || status == 410) return notFound
    val kind = api?.kind
    if (kind == ApiFailureKind.NETWORK || kind == ApiFailureKind.TIMEOUT) {
        return "$loadFailed: $networkError"
    }
    val server = api?.serverMessage?.trim().orEmpty()
        .ifBlank { failure.message?.trim().orEmpty() }
    return if (server.isNotBlank()) "$loadFailed: $server" else loadFailed
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
    fun normalizeUsernameRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val raw = randomUsername(rnd)
            val name = randomString(rnd)
            assertEquals(normalizeUsernameOracle(raw), ExplorePublicProfilePolicy.normalizeUsername(raw))
            assertEquals(isValidUsernameOracle(name), ExplorePublicProfilePolicy.isValidUsername(name))
        }
    }

    @Test
    fun displayLoadErrorRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val failure = randomError(rnd)
            val notFound = randomString(rnd)
            val loadFailed = randomString(rnd)
            val networkError = randomString(rnd)
            assertEquals(
                displayLoadErrorOracle(failure, notFound, loadFailed, networkError),
                ExplorePublicProfilePolicy.displayLoadError(failure, notFound, loadFailed, networkError)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // normalizeUsername: trim, decode once, strip quotes/@/path/query.
        val names = listOf(
            "  @ada  " to "ada",
            "%40ada" to "ada",
            "u/ada" to "ada",
            "https://chat.mdou.me/u/ada?x=1" to "ada",
            "   " to "",
            null to "",
            "@@ada" to "ada",
            "'@ada'" to "ada",
            "\"ada\"" to "ada",
            "ada#section?x=1" to "ada",
            "u/ada?x=1#frag" to "ada",
            "%2540ada" to "%40ada",
            "%2Fada" to "ada",
            "%20ada%20" to "ada",
            "%zzada" to "%zzada",
            "'ada\"" to "ada",
            "\"'ada'\"" to "ada",
            "@@@" to "",
            "ada/" to "",
            "/ada" to "ada",
            "@ ada" to "ada",
            "u%2Fada" to "ada",
            "a+b" to "a b",
            "%2B" to "+",
            "%" to "%",
            "%2" to "%2",
            // Malformed UTF-8 decodes to U+FFFD instead of throwing.
            "%FF" to "\uFFFD",
            "%C3%A9" to "é",
            "%C3%28" to "\uFFFD("
        )
        for ((raw, want) in names) {
            assertEquals(want, ExplorePublicProfilePolicy.normalizeUsername(raw), "normalize($raw)")
        }

        // isValidUsername is just isNotBlank: NBSP is blank, NEL is not.
        assertFalse(ExplorePublicProfilePolicy.isValidUsername(""))
        assertFalse(ExplorePublicProfilePolicy.isValidUsername("   "))
        assertFalse(ExplorePublicProfilePolicy.isValidUsername("\u00a0"))
        assertTrue(ExplorePublicProfilePolicy.isValidUsername("\u0085"))
        assertTrue(ExplorePublicProfilePolicy.isValidUsername("ada"))
        assertTrue(ExplorePublicProfilePolicy.isValidUsername("@ada"))
        assertTrue(ExplorePublicProfilePolicy.isValidUsername("a b"))

        // displayLoadError: 404/410 beat the kind; NETWORK/TIMEOUT use the
        // network copy; otherwise the trimmed server copy, else loadFailed.
        fun api(status: Int?, server: String?, kind: ApiFailureKind = ApiFailureKind.HTTP) =
            ApiException(kind = kind, statusCode = status, serverMessage = server)
        assertEquals("not-found", ExplorePublicProfilePolicy.displayLoadError(api(404, null), "not-found", "load-failed", "network"))
        assertEquals("not-found", ExplorePublicProfilePolicy.displayLoadError(api(410, "gone"), "not-found", "load-failed", "network"))
        assertEquals("not-found", ExplorePublicProfilePolicy.displayLoadError(api(404, null, ApiFailureKind.NETWORK), "not-found", "load-failed", "network"))
        assertEquals("load-failed: network", ExplorePublicProfilePolicy.displayLoadError(api(null, null, ApiFailureKind.NETWORK), "not-found", "load-failed", "network"))
        assertEquals("load-failed: network", ExplorePublicProfilePolicy.displayLoadError(api(null, null, ApiFailureKind.TIMEOUT), "not-found", "load-failed", "network"))
        assertEquals("load-failed: boom", ExplorePublicProfilePolicy.displayLoadError(api(500, "boom"), "not-found", "load-failed", "network"))
        assertEquals("load-failed: boom", ExplorePublicProfilePolicy.displayLoadError(api(500, "  boom  "), "not-found", "load-failed", "network"))
        assertEquals("load-failed", ExplorePublicProfilePolicy.displayLoadError(api(500, null), "not-found", "load-failed", "network"))
        assertEquals("load-failed", ExplorePublicProfilePolicy.displayLoadError(api(500, "   "), "not-found", "load-failed", "network"))
        assertEquals("load-failed: boom", ExplorePublicProfilePolicy.displayLoadError(RuntimeException("boom"), "not-found", "load-failed", "network"))
        assertEquals("load-failed", ExplorePublicProfilePolicy.displayLoadError(RuntimeException(null as String?), "not-found", "load-failed", "network"))
    }
}
