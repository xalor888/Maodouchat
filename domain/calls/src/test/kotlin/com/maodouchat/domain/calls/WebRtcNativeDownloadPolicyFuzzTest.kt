package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebRtcNativeDownloadPolicy 四个纯函数的随机 oracle fuzz。
 * oracle 按生产源码逐字转录；随机输入固定种子，可复现。
 */
class WebRtcNativeDownloadPolicyFuzzTest {

    private val random = Random(20261122)

    private fun isHex64(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    // ---- requestAbi oracle：首个 hosted ABI，否则首个 arm64 前缀，否则默认 arm64-v8a ----

    private fun expectedAbi(abis: Array<String>): String {
        for (abi in abis) {
            if (abi in WebRtcNativeDownloadPolicy.HOSTED_ABIS) return abi
        }
        return abis.firstOrNull { it.startsWith("arm64") } ?: "arm64-v8a"
    }

    @Test
    fun requestAbiMatchesFirstHostedOrArm64PrefixOracle() {
        val pool = listOf(
            "arm64-v8a", "x86_64", "armeabi-v7a", "arm64-custom", "ARM64-V8A",
            "x86", "mips", "", "arm64", "x86_64-v2", "armv7", "arm64-v8a"
        )
        repeat(300) {
            val abis = Array(random.nextInt(0, 6)) { pool[random.nextInt(pool.size)] }
            assertEquals(expectedAbi(abis), WebRtcNativeDownloadPolicy.requestAbi(abis))
        }
    }

    // ---- isOriginTrusted oracle：按生产源码逐字转录 ----

    private fun expectedTrusted(url: String, runtimeHost: String?): Boolean {
        val trimmed = url.trim().lowercase()
        if (trimmed.startsWith("http://10.0.2.2:") ||
            trimmed.startsWith("http://localhost:") ||
            trimmed.startsWith("http://127.0.0.1:")
        ) {
            return true
        }
        if (!trimmed.startsWith("https://")) return false
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').trim()
        val isOfficial = host.equals("chat.mdou.me", ignoreCase = true) ||
            host.equals("mdou.me", ignoreCase = true) ||
            host.endsWith(".mdou.me", ignoreCase = true)
        if (isOfficial) return true
        val runtime = runtimeHost?.trim().orEmpty()
        return runtime.isNotBlank() && host.equals(runtime, ignoreCase = true)
    }

    private fun randomUrl(): String {
        val scheme = listOf("https", "http", "ftp", "HTTPS", "Http", "").random(random)
        val host = listOf(
            "chat.mdou.me", "mdou.me", "files.mdou.me", "a.b.mdou.me",
            "chat.mdou.me.evil.com", "evilmdou.me", "myserver.example",
            "localhost", "127.0.0.1", "10.0.2.2", "example.com",
            "CHAT.MDOU.ME", "Files.Mdou.Me", "", "chat.mdou.me "
        ).random(random)
        val port = listOf("", ":8443", ":8080", ":443").random(random)
        val path = listOf("/lib/arm64-v8a", "/lib", "/", "").random(random)
        val raw = when (random.nextInt(0, 5)) {
            0 -> "$scheme://$host$port$path"
            1 -> "$scheme:$host$port$path"
            2 -> host + path
            3 -> scheme
            else -> "$scheme://$host$port$path"
        }
        val padded = when (random.nextInt(0, 4)) {
            0 -> "  $raw  "
            1 -> "\t$raw"
            else -> raw
        }
        return padded
    }

    @Test
    fun isOriginTrustedMatchesOracle() {
        val runtimes = listOf<String?>(null, "", "  ", "myserver.example", "other.example", "MYSERVER.EXAMPLE")
        repeat(300) {
            val url = randomUrl()
            val runtimeHost = runtimes[random.nextInt(runtimes.size)]
            assertEquals(
                expectedTrusted(url, runtimeHost),
                WebRtcNativeDownloadPolicy.isOriginTrusted(url, runtimeHost),
                "url=<$url> runtimeHost=<$runtimeHost>"
            )
        }
    }

    // ---- parseChecksum oracle：header 优先，否则 ETag 去 W/ 前缀/引号 ----

    private fun expectedChecksum(etag: String?, contentSha256: String?): String? {
        contentSha256?.trim()?.lowercase()?.takeIf { isHex64(it) }?.let { return it }
        val raw = etag?.trim().orEmpty()
        if (raw.isBlank()) return null
        val stripped = raw.removePrefix("W/").removePrefix("w/").trim().trim('"').lowercase()
        return stripped.takeIf { isHex64(it) }
    }

    private fun randomChecksumFragment(): String? {
        val hex = "0123456789abcdefABCDEF"
        return when (random.nextInt(0, 8)) {
            0 -> null
            1 -> ""
            2 -> "   "
            3 -> (1..random.nextInt(0, 72)).map { hex[random.nextInt(hex.length)] }.joinToString("")
            4 -> "W/\"" + (1..64).map { hex[random.nextInt(16)] }.joinToString("") + "\""
            5 -> "\"" + (1..random.nextInt(0, 70)).map { "0123456789abcdefgGz \"".random(random) }.joinToString("") + "\""
            6 -> "w/" + (1..random.nextInt(0, 70)).map { hex[random.nextInt(hex.length)] }.joinToString("")
            else -> (1..random.nextInt(0, 70)).map { "0123456789abcdefgGz \"\t".random(random) }.joinToString("")
        }
    }

    @Test
    fun parseChecksumMatchesOracle() {
        repeat(300) {
            val etag = randomChecksumFragment()
            val header = randomChecksumFragment()
            assertEquals(
                expectedChecksum(etag, header),
                WebRtcNativeDownloadPolicy.parseChecksum(etag, header),
                "etag=<$etag> header=<$header>"
            )
        }
    }

    // ---- verifyIntegrity oracle：actual 先判空/长度，再比小写 ----

    private fun expectedIntegrity(actualSha256: String, expectedChecksum: String?): Boolean {
        if (actualSha256.isBlank() || actualSha256.length != 64) return false
        val expected = expectedChecksum?.trim()?.lowercase() ?: return false
        return actualSha256.trim().lowercase() == expected
    }

    @Test
    fun verifyIntegrityMatchesOracle() {
        val hex = "0123456789abcdefABCDEF"
        val sha = "a".repeat(64)
        repeat(300) {
            val actual = when (random.nextInt(0, 6)) {
                0 -> sha
                1 -> sha.uppercase()
                2 -> (1..random.nextInt(0, 72)).map { hex[random.nextInt(hex.length)] }.joinToString("")
                3 -> " " + sha + " "
                4 -> " ".repeat(64)
                else -> (1..64).map { "ab ".random(random) }.joinToString("")
            }
            val expected = when (random.nextInt(0, 5)) {
                0 -> null
                1 -> sha
                2 -> sha.uppercase()
                3 -> "b".repeat(64)
                else -> (1..random.nextInt(0, 70)).map { hex[random.nextInt(hex.length)] }.joinToString("")
            }
            assertEquals(
                expectedIntegrity(actual, expected),
                WebRtcNativeDownloadPolicy.verifyIntegrity(actual, expected),
                "actual=<$actual> expected=<$expected>"
            )
        }
    }

    // ---- 边界钉住 ----

    @Test
    fun hostedAbisAndRequestAbiBoundariesPinned() {
        assertEquals(setOf("arm64-v8a", "x86_64"), WebRtcNativeDownloadPolicy.HOSTED_ABIS)
        assertEquals("arm64-v8a", WebRtcNativeDownloadPolicy.requestAbi(arrayOf()))
        // 大小写敏感：全大写既不在 hosted 集里，也不是 arm64 前缀。
        assertEquals("arm64-v8a", WebRtcNativeDownloadPolicy.requestAbi(arrayOf("ARM64-V8A")))
        // 按输入顺序取第一个 hosted ABI，不偏好 x86_64。
        assertEquals("arm64-v8a", WebRtcNativeDownloadPolicy.requestAbi(arrayOf("arm64-v8a", "x86_64")))
        assertEquals("x86_64", WebRtcNativeDownloadPolicy.requestAbi(arrayOf("x86_64", "arm64-v8a")))
        assertEquals("arm64-custom", WebRtcNativeDownloadPolicy.requestAbi(arrayOf("mips", "arm64-custom")))
        assertEquals("arm64-v8a", WebRtcNativeDownloadPolicy.requestAbi(arrayOf("armeabi-v7a")))
    }

    @Test
    fun checksumAndIntegrityBoundariesPinned() {
        val sha = "a".repeat(64)
        // W/ 与 w/ 各只剥一次、按顺序剥：W/w/ 剥干净，w/W/ 只剥掉 w/、残留 W/ 后长度超限。
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("W/w/\"$sha\"", null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("w/W/\"$sha\"", null))
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("\"\"$sha\"\"", null))
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("W/ $sha ", null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("a".repeat(63), null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("a".repeat(65), null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("g".repeat(64), null))
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum(null, sha.uppercase()))
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("\"$sha\"", "g".repeat(64)))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum(null, null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("   ", "   "))
        // 长度先于 trim 判定：64 个空白算 blank、65 字符（含首尾空格）直接失败。
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(" ".repeat(64), sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(sha + " ", sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(" $sha ", sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity("a".repeat(63) + " ", sha))
        // 长度恰 64 且 trim 后相等才为真：内部空白不受 trim 影响。
        assertTrue(WebRtcNativeDownloadPolicy.verifyIntegrity("a".repeat(32) + " " + "a".repeat(31), "a".repeat(32) + " " + "a".repeat(31)))
        assertTrue(WebRtcNativeDownloadPolicy.verifyIntegrity(sha.uppercase(), sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(sha, null))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(sha, ""))
    }
}
