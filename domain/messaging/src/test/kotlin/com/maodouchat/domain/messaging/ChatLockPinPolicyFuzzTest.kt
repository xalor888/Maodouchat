package com.maodouchat.domain.messaging

import com.maodouchat.core.session.VerificationOutcome
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，名字/错误串里出现分隔符也不怕。
private fun encStr194p(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr194p(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt194p(s: String?): String = if (s == null) "NULL" else "S:" + encStr194p(s)
private fun decOpt194p(h: String): String? = if (h == "NULL") null else decStr194p(h.substring(2))

private fun hex194p(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

// PBKDF2 的独立重实现：直接走 JCA 原语，不走生产 PinSecurityPolicy。
private fun pbkdf2Oracle194p(pin: String, saltHex: String, iterations: Int): String {
    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
    val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    spec.clearPassword()
    return hex194p(bytes)
}

private fun sha256Oracle194p(s: String): String =
    hex194p(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

// 恒定时间比较的独立重实现：长度不等即 false。
private fun constantTimeEquals194p(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}

// verifyPin 的独立重实现：只比结果种类 + 是否需升级 + 升级哈希是否存在（升级哈希含真随机 salt，值本身不可复现）。
private fun verifyOracle194p(pin: String, storedHash: String, storedSalt: String): String {
    if (storedHash.isBlank()) return "Corrupted"
    if (storedHash.startsWith("pbkdf2\$")) {
        val parts = storedHash.split("\$")
        if (parts.size != 4) return "Corrupted"
        val iter = parts[1].toIntOrNull() ?: return "Corrupted"
        val computed = pbkdf2Oracle194p(pin, parts[2], iter)
        if (!constantTimeEquals194p(parts[3], computed)) return "Mismatch"
        val needsUpgrade = iter < 600_000
        return "Success|" + (if (needsUpgrade) "1" else "0") + "|" + (if (needsUpgrade) "1" else "0")
    }
    val computed = sha256Oracle194p(pin + storedSalt)
    if (!constantTimeEquals194p(storedHash, computed)) return "Mismatch"
    return "Success|1|1"
}

private fun verifyProd194p(pin: String, storedHash: String, storedSalt: String): String {
    return try {
        when (val out = ChatLockPinPolicy.verifyStoredPin(pin, storedHash, storedSalt)) {
            is VerificationOutcome.Success ->
                "Success|" + (if (out.needsUpgrade) "1" else "0") + "|" + (if (out.upgradedHash != null) "1" else "0")
            VerificationOutcome.Mismatch -> "Mismatch"
            VerificationOutcome.Corrupted -> "Corrupted"
        }
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

private fun verifyOracleSafe194p(pin: String, storedHash: String, storedSalt: String): String {
    return try {
        verifyOracle194p(pin, storedHash, storedSalt)
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

private data class VerifyCase194p(val pin: String, val storedHash: String, val storedSalt: String)

private val PIN_ALPHABET194p = listOf(
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    "a", "b", "c", "X", "Y", "Z", "_", "-", "\u00e9", "\u4e2d", "\ud83d\ude00"
)
private val SALT_HEX194p = "0123456789abcdefABCDEF"

private fun randomSaltHex194p(r: Random): String {
    return when (r.nextInt(10)) {
        0 -> ""
        1 -> "zz"
        2 -> "ab"
        3 -> "abc"
        else -> (0 until r.nextInt(2, 34)).joinToString("") { SALT_HEX194p[r.nextInt(SALT_HEX194p.length)].toString() }
    }
}

private fun randomVerify194p(r: Random): VerifyCase194p {
    val pin = (0 until r.nextInt(0, 10)).joinToString("") { PIN_ALPHABET194p[r.nextInt(PIN_ALPHABET194p.size)] }
    val storedSalt = (0 until r.nextInt(0, 8)).joinToString("") { "abcXYZ019 _-".get(r.nextInt(12)).toString() }
    val shape = r.nextInt(10)
    val storedHash = when {
        shape == 0 -> ""
        shape == 1 -> "   "
        shape == 2 -> "pbkdf2\$1\$ab"
        shape == 3 -> "pbkdf2\$xx\$ab\$cd"
        shape == 4 -> "pbkdf2\$99999999999999999999\$ab\$cd"
        shape == 5 -> {
            val iter = listOf(1, 2, 3, 7, 50, 500, 2000)[r.nextInt(7)]
            val salt = randomSaltHex194p(r)
            val hash = if (r.nextInt(3) == 0) {
                try {
                    pbkdf2Oracle194p(pin, salt, iter)
                } catch (e: Exception) {
                    "00".repeat(32)
                }
            } else {
                "0123456789abcdef".repeat(4)
            }
            "pbkdf2\$$iter\$$salt\$$hash"
        }
        shape == 6 -> {
            // 满迭代向量很贵（单例约 0.2 秒）：只在少数情形下用 600000，其余用小轮数正确哈希。
            val iter = if (r.nextInt(20) == 0) 600000 else listOf(1, 2, 7, 50)[r.nextInt(4)]
            "pbkdf2\$$iter\$00112233445566778899aabbccddeeff\$" + pbkdf2Oracle194p(pin, "00112233445566778899aabbccddeeff", iter)
        }
        shape == 7 -> sha256Oracle194p(pin + storedSalt)
        shape == 8 -> "0123456789abcdef".repeat(4)
        else -> "pbkdf2\$0\$ab\$cd"
    }
    return VerifyCase194p(pin, storedHash, storedSalt)
}

// ---------- limiter oracle ----------

// RateLimiter 的独立重实现：与生产逐行同语义，假时钟由调用方逐操作推进。
private class LimiterOracle194p {
    private val failureCounts = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    var now: Long = 0L

    fun recordFailure(key: String): Long {
        val failures = (failureCounts[key] ?: 0) + 1
        return if (failures >= 5) {
            lockedUntil[key] = now + 30_000L
            failureCounts.remove(key)
            30_000L
        } else {
            failureCounts[key] = failures
            0L
        }
    }

    fun recordSuccess(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun reset(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun resetAll() {
        failureCounts.clear()
        lockedUntil.clear()
    }

    fun remainingLockoutMs(key: String): Long {
        val until = lockedUntil[key] ?: return 0L
        val remaining = until - now
        return if (remaining > 0L) remaining else 0L
    }

    fun isLocked(key: String): Boolean = remainingLockoutMs(key) > 0L
}

private data class LimOp194p(val kind: String, val key: String, val now: Long)

private val LIM_KEYS194p = listOf("", "k1", "k2", "alice", "a/b", "\u00e9", "\ud83d\ude00", "x".repeat(40))
private val LIM_NOWS194p = listOf(
    0L, 1L, -1L, 1000L, 29_999L, 30_000L, 30_001L, 60_000L,
    Long.MAX_VALUE - 40_000L, Long.MAX_VALUE - 1L, Long.MAX_VALUE, Long.MIN_VALUE, 86400000L
)

private fun randomLimiterScript194p(r: Random): List<LimOp194p> {
    val kinds = listOf("fail", "fail", "fail", "succ", "reset", "resetAll", "locked", "remain")
    return List(r.nextInt(1, 13)) {
        val kind = kinds[r.nextInt(kinds.size)]
        val key = if (r.nextInt(4) == 0) LIM_KEYS194p[r.nextInt(LIM_KEYS194p.size)]
        else (0 until r.nextInt(0, 6)).joinToString("") { "ab12-_".get(r.nextInt(6)).toString() }
        val now = if (r.nextInt(3) == 0) LIM_NOWS194p[r.nextInt(LIM_NOWS194p.size)]
        else r.nextLong(-1000, 200_000)
        LimOp194p(kind, key, now)
    }
}

// ---------- tests ----------

class ChatLockPinPolicyFuzzTest {

    @Test
    fun pinVerifyDifferential194() {
        val r = Random(194001)
        repeat(300) {
            val c = randomVerify194p(r)
            assertEquals(verifyOracleSafe194p(c.pin, c.storedHash, c.storedSalt), verifyProd194p(c.pin, c.storedHash, c.storedSalt))
        }
    }

    @Test
    fun pinLimiterDifferential194() {
        val r = Random(194002)
        repeat(300) {
            val script = randomLimiterScript194p(r)
            val oracle = LimiterOracle194p()
            var now = 0L
            val prod = ChatLockPinPolicy.newLimiter(clock = { now })
            val keys = HashSet<String>()
            for (op in script) {
                now = op.now
                oracle.now = op.now
                keys.add(op.key)
                val retO: String
                val retP: String
                when (op.kind) {
                    "fail" -> {
                        retO = oracle.recordFailure(op.key).toString()
                        retP = prod.recordFailure(op.key).toString()
                    }
                    "succ" -> {
                        oracle.recordSuccess(op.key)
                        prod.recordSuccess(op.key)
                        retO = "ok"
                        retP = "ok"
                    }
                    "reset" -> {
                        oracle.reset(op.key)
                        prod.reset(op.key)
                        retO = "ok"
                        retP = "ok"
                    }
                    "resetAll" -> {
                        oracle.resetAll()
                        prod.resetAll()
                        retO = "ok"
                        retP = "ok"
                    }
                    "locked" -> {
                        retO = oracle.isLocked(op.key).toString()
                        retP = prod.isLocked(op.key).toString()
                    }
                    else -> {
                        retO = oracle.remainingLockoutMs(op.key).toString()
                        retP = prod.remainingLockoutMs(op.key).toString()
                    }
                }
                assertEquals(retO, retP)
                // 失败计数生产侧不可见：逐键比生产可见状态（是否锁定 + 剩余锁定时长）。
                for (k in keys.sorted()) {
                    assertEquals(oracle.isLocked(k), prod.isLocked(k))
                    assertEquals(oracle.remainingLockoutMs(k), prod.remainingLockoutMs(k))
                }
            }
        }
    }

    @Test
    fun pinPinned194() {
        assertEquals(5, ChatLockPinPolicy.MAX_FAILURES)
        assertEquals(30_000L, ChatLockPinPolicy.LOCKOUT_MS)
        // 600k 轮满迭代向量：匹配且无需升级，升级哈希为 null。
        val stored600k = "pbkdf2\$600000\$00112233445566778899aabbccddeeff\$91d8c5ee08baa388beb6a7a3dc28f7954545651efef819d00cfe5fea3e8c217c"
        assertEquals("Success|0|0", verifyProd194p("1234", stored600k, ""))
        assertEquals("Success|0|0", verifyOracleSafe194p("1234", stored600k, ""))
        // 低轮数匹配：需升级，升级哈希存在。
        val storedIter1 = "pbkdf2\$1\$00112233445566778899aabbccddeeff\$fdef661be5e77098d6a0ecc0348d609deba2b9354279c40eb1c1d1075b592309"
        assertEquals("Success|1|1", verifyProd194p("1234", storedIter1, ""))
        // 旧版 SHA-256(pin+salt) 匹配：强制升级。
        assertEquals("Success|1|1", verifyProd194p("1234", "4b3bed8af7b7612e8c1e25f63ba24496f5b16b2df44efb2db7ce3cb24b7e96f7", "salt"))
        // 错 PIN / 损坏串 / 非法轮数与 salt。
        assertEquals("Mismatch", verifyProd194p("9999", stored600k, ""))
        assertEquals("Corrupted", verifyProd194p("1234", "", ""))
        assertEquals("Corrupted", verifyProd194p("1234", "pbkdf2\$1\$ab", ""))
        assertEquals("Corrupted", verifyProd194p("1234", "pbkdf2\$xx\$ab\$cd", ""))
        assertEquals("THROW:IllegalArgumentException", verifyProd194p("1234", "pbkdf2\$0\$ab\$cd", ""))
        assertEquals("THROW:NumberFormatException", verifyProd194p("1234", "pbkdf2\$1\$zz\$cd", ""))
        assertEquals("THROW:IllegalArgumentException", verifyProd194p("1234", "pbkdf2\$1\$\$cd", ""))
        assertEquals("THROW:IllegalArgumentException", verifyOracleSafe194p("1234", "pbkdf2\$0\$ab\$cd", ""))
        assertEquals("THROW:NumberFormatException", verifyOracleSafe194p("1234", "pbkdf2\$1\$zz\$cd", ""))
        // 限流器 pinned：5 次失败锁定 30 秒，心跳（成功）清零，锁止时刻恰好到期即解锁。
        var now = 1000L
        val lim = ChatLockPinPolicy.newLimiter(clock = { now })
        repeat(4) { assertEquals(0L, lim.recordFailure("k")) }
        assertFalse(lim.isLocked("k"))
        assertEquals(30_000L, lim.recordFailure("k"))
        assertTrue(lim.isLocked("k"))
        assertEquals(30_000L, lim.remainingLockoutMs("k"))
        now = 1000L + 29_999L
        assertEquals(1L, lim.remainingLockoutMs("k"))
        now = 1000L + 30_000L
        assertFalse(lim.isLocked("k"))
        assertEquals(0L, lim.remainingLockoutMs("k"))
        lim.recordSuccess("k")
        repeat(5) { lim.recordFailure("k") }
        assertTrue(lim.isLocked("k"))
        lim.recordSuccess("k")
        assertFalse(lim.isLocked("k"))
        // 锁止时刻溢出回绕：now 接近 Long.MAX_VALUE 时 until 为负，但剩余时长
        // 再算一次差又回绕回 30000，仍判定锁定（三方驱动已一致验证，非解锁）。
        now = Long.MAX_VALUE - 10L
        val lim2 = ChatLockPinPolicy.newLimiter(clock = { now })
        repeat(5) { lim2.recordFailure("o") }
        assertTrue(lim2.isLocked("o"))
        assertEquals(30_000L, lim2.remainingLockoutMs("o"))
    }
}
