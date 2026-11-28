package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ServerUrlNormalizationPolicy fuzz（清单 931 行第十四块，簇 3）。
 *
 * 先钉清两处幂等语义再写 oracle（源码实测）：
 * - `normalizeBaseUrl` 幂等：真（trim + 去尾 `/` + 仅 scheme 小写，二次调用是 no-op）。
 * - `wsUrlFor` 幂等：假——每次调用都追加一段 `/ws`，恒有
 *   `wsUrlFor(wsUrlFor(x)) == wsUrlFor(x) + "/ws"`；不得断言它幂等。
 * - `normalize` 与 `wsUrlFor` 的交换只在 scheme 已小写（或无 scheme）时成立：
 *   大写非 http(s) scheme（如 `FTP://`）经 normalize 会被小写，wsUrlFor 侧保留原大小写。
 */
class ServerUrlNormalizationPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val schemes = listOf(
            "http", "https", "HTTP", "HTTPS", "HtTp", "ws", "wss", "WS", "ftp", "FTP", "", "h", "a"
        )
        private val hosts = listOf(
            "a.com", "api.example.com", "API.Example.COM", "host", "a.b.c", "x", "192.168.1.1", ""
        )
        private val suffixes = listOf(
            "", "/", "//", "/v1", "/a/b", "/ws", ":8080", ":8080/v1", "/p?q=1", "/a b"
        )
    }

    private fun randomUrl(random: Random): String {
        val leadingSpaces = if (random.nextBoolean()) "  " else ""
        val trailingSpaces = if (random.nextBoolean()) "  " else ""
        val scheme = schemes[random.nextInt(schemes.size)]
        val schemePart = when {
            scheme.isEmpty() && random.nextBoolean() -> ""
            scheme.isEmpty() -> "://"
            random.nextInt(10) < 8 -> "$scheme://"
            else -> scheme
        }
        val host = hosts[random.nextInt(hosts.size)]
        val suffix = suffixes[random.nextInt(suffixes.size)]
        val trailingSlashes = "/".repeat(random.nextInt(0, 3))
        return leadingSpaces + schemePart + host + suffix + trailingSlashes + trailingSpaces
    }

    private fun schemeIsLowercaseOrAbsent(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd <= 0) return true
        val scheme = normalized.substring(0, schemeEnd)
        return scheme == scheme.lowercase()
    }

    @Test
    fun `normalizeIsIdempotent`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val input = randomUrl(random)
            val once = ServerUrlNormalizationPolicy.normalizeBaseUrl(input)
            assertEquals(
                once, ServerUrlNormalizationPolicy.normalizeBaseUrl(once),
                "fuzz 迭代 #$i：normalize($input) 必须幂等"
            )
        }
    }

    @Test
    fun `normalizeKeepsHostCaseAndStripsOnlySchemeCaseAndSlashes`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val input = randomUrl(random)
            val normalized = ServerUrlNormalizationPolicy.normalizeBaseUrl(input)
            assertEquals(normalized, normalized.trim(), "fuzz 迭代 #$i：normalize 结果不得含首尾空白")
            assertFalse(normalized.endsWith("/"), "fuzz 迭代 #$i：normalize 结果不得以 / 结尾")
            val schemeEnd = normalized.indexOf("://")
            if (schemeEnd > 0) {
                val scheme = normalized.substring(0, schemeEnd)
                assertEquals(scheme, scheme.lowercase(), "fuzz 迭代 #$i：scheme 必须已小写")
            }
        }
    }

    @Test
    fun `wsUrlForAlwaysEndsWithWsPath`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val input = randomUrl(random)
            val ws = ServerUrlNormalizationPolicy.wsUrlFor(input)
            assertTrue(ws.endsWith("/ws"), "fuzz 迭代 #$i：wsUrlFor($input) 必须以 /ws 结尾，实际 $ws")
        }
    }

    @Test
    fun `wsUrlForAppliedTwiceAppendsExactlyOneWsSegment`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val input = randomUrl(random)
            val once = ServerUrlNormalizationPolicy.wsUrlFor(input)
            assertEquals(
                "$once/ws", ServerUrlNormalizationPolicy.wsUrlFor(once),
                "fuzz 迭代 #$i：wsUrlFor 再次调用应恰好多一段 /ws，不得幂等"
            )
        }
    }

    @Test
    fun `wsUrlForAndNormalizeCommuteOnLowercaseSchemes`() {
        val random = Random(0x6F_2026)
        var checked = 0
        repeat(RANDOM_ITERATIONS) { i ->
            val input = randomUrl(random)
            if (!schemeIsLowercaseOrAbsent(input)) return@repeat
            checked++
            assertEquals(
                ServerUrlNormalizationPolicy.wsUrlFor(input),
                ServerUrlNormalizationPolicy.wsUrlFor(ServerUrlNormalizationPolicy.normalizeBaseUrl(input)),
                "fuzz 迭代 #$i：小写 scheme 下 wsUrlFor(normalize(x)) 必须等于 wsUrlFor(x)"
            )
            assertEquals(
                ServerUrlNormalizationPolicy.wsUrlFor(input),
                ServerUrlNormalizationPolicy.normalizeBaseUrl(ServerUrlNormalizationPolicy.wsUrlFor(input)),
                "fuzz 迭代 #$i：小写 scheme 下 normalize(wsUrlFor(x)) 必须等于 wsUrlFor(x)"
            )
        }
        assertTrue(checked > 50, "随机输入里小写 scheme 样本应占多数，实际 $checked")
    }

    @Test
    fun `schemeMappingBoundaries`() {
        assertEquals("wss://api.example.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("https://api.example.com"))
        assertEquals("wss://api.example.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("HTTPS://api.example.com/"))
        assertEquals("ws://api.example.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("Http://api.example.com"))
        // ws/wss 输入走 else 分支，只补 /ws，不改 scheme。
        assertEquals("wss://a.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("wss://a.com"))
        assertEquals("ws://a.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("ws://a.com"))
        // 非 http(s) scheme 走 else 分支，大小写原样保留。
        assertEquals("FTP://a.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("FTP://a.com"))
        assertEquals("ftp://a.com/ws", ServerUrlNormalizationPolicy.wsUrlFor("ftp://a.com"))
        // 路径与端口保留；host 大小写保留；空串与 "://" 原样走 else 分支。
        assertEquals("wss://a.com:8080/v1/ws", ServerUrlNormalizationPolicy.wsUrlFor("https://a.com:8080/v1"))
        assertEquals("https://API.Example.COM", ServerUrlNormalizationPolicy.normalizeBaseUrl("HTTPS://API.Example.COM/"))
        assertEquals("/ws", ServerUrlNormalizationPolicy.wsUrlFor(""))
        assertEquals("", ServerUrlNormalizationPolicy.normalizeBaseUrl(""))
        // "://" 经 trimEnd('/') 先剥成 ":"（schemeEnd 随之消失），再走 else 分支。
        assertEquals(":/ws", ServerUrlNormalizationPolicy.wsUrlFor("://"))
        assertEquals(":", ServerUrlNormalizationPolicy.normalizeBaseUrl("://"))
        // normalize 只小写 scheme，不动其余部分；去尾斜杠全剥。
        assertEquals("https://a.com", ServerUrlNormalizationPolicy.normalizeBaseUrl("https://a.com//"))
        assertEquals("https://a.com", ServerUrlNormalizationPolicy.normalizeBaseUrl("  https://a.com/  "))
    }
}
