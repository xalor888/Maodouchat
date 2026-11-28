// Fuzz / random-oracle tests for FakeChatPolicy
// (checklist line 931, 128th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: show/valid/
// weak are pure boolean/string checks; verifyPinHash is the
// iterations:b64(salt):b64(hash) parse + PBKDF2-HMAC-SHA256 compare.
// encodePinHash is NOT covered here (random salt, no deterministic
// expectation). No FakeChatPolicy fuzz test existed before this block.
package com.maodouchat.domain.messaging

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- shared oracle ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

private fun pbkdf2Oracle(pin: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun fcpShowOracle(
    enabled: Boolean,
    hasPin: Boolean,
    unlockedForCurrentUser: Boolean,
    relockOnBackground: Boolean,
    backgroundAtMillis: Long,
): Boolean {
    if (!enabled || !hasPin) return false
    if (unlockedForCurrentUser) {
        if (!relockOnBackground) return false
        if (backgroundAtMillis <= 0L) return false
    }
    return true
}

private fun fcpValidOracle(pin: String): Boolean =
    pin.length in 4..12 && pin.all { Character.isDigit(it) }

private fun fcpWeakOracle(pin: String): Boolean = pin == "0000" || pin == "1234"

private fun fcpVerifyOracle(pin: String, stored: String): Boolean {
    val parts = stored.split(':')
    if (parts.size != 3) return false
    val iterations = parts[0].toIntOrNull() ?: return false
    val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
    val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
    val actual = pbkdf2Oracle(pin, salt, iterations)
    return MessageDigest.isEqual(expected, actual)
}

private val FCP_PIN_POOL = arrayOf(
    "", "1234", "0000", "12345", "123", "123456789012", "1234567890123",
    "12a4", "١٢٣٤", "１２３４", "a\uD800b", "\uDC00", "000", "9999",
    "１２３４５６７８９０", "०१२३"
)

private val FCP_BG_POOL = longArrayOf(
    -2, -1, 0, 1, 2, 100, Long.MIN_VALUE, Long.MAX_VALUE
)

private val FCP_ITER_POOL = intArrayOf(1, 2, 10, 100, 1000, 2000)

private val FCP_BAD_ITERS = arrayOf(
    "abc", "", " 1", "1 ", "+1", "-1", "0", "99999999999999999999", "1.5", "0x10"
)

private fun genFcpBool(rnd: Random): Boolean = rnd.nextBoolean()

private fun genFcpPin(rnd: Random): String =
    if (rnd.nextInt(3) == 0) FCP_PIN_POOL[rnd.nextInt(FCP_PIN_POOL.size)] else randomText(rnd, 12)

private fun genFcpBgAt(rnd: Random): Long =
    if (rnd.nextInt(3) == 0) FCP_BG_POOL[rnd.nextInt(FCP_BG_POOL.size)] else rnd.nextLong(-10, 11)

private fun validStoredOracle(pin: String, rnd: Random, corrupt: Boolean = false): String {
    val iters = FCP_ITER_POOL[rnd.nextInt(FCP_ITER_POOL.size)]
    val salt = ByteArray(rnd.nextInt(1, 17)) { rnd.nextInt(256).toByte() }
    val hash = pbkdf2Oracle(pin, salt, iters)
    val shown = if (corrupt) hash.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() } else hash
    val enc = Base64.getEncoder()
    return "$iters:${enc.encodeToString(salt)}:${enc.encodeToString(shown)}"
}

private fun malformedStoredOracle(rnd: Random): String {
    return when (rnd.nextInt(8)) {
        0 -> "a:b"
        1 -> "a:b:c:d"
        2 -> FCP_BAD_ITERS[rnd.nextInt(FCP_BAD_ITERS.size)] + ":AA==:AA=="
        3 -> "1:!!!:AA=="
        4 -> "1:AA==:!!!"
        5 -> "1::AA=="
        6 -> "1:AA==:"
        else -> "1:é:AA=="
    }
}

private fun genFcpStored(rnd: Random, pin: String): String {
    return when (rnd.nextInt(5)) {
        0 -> validStoredOracle(pin, rnd)
        1 -> validStoredOracle(genFcpPin(rnd), rnd)
        2 -> validStoredOracle(pin, rnd, corrupt = true)
        3 -> malformedStoredOracle(rnd)
        else -> randomText(rnd, 24)
    }
}

class FakeChatPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // shouldShowFake: disabled or pin-less never shows; an unlocked
        // session only shows when relock-on-background saw a real
        // background timestamp.
        assertFalse(FakeChatPolicy.shouldShowFake(false, true, false, false, 0L))
        assertFalse(FakeChatPolicy.shouldShowFake(true, false, false, false, 0L))
        assertTrue(FakeChatPolicy.shouldShowFake(true, true, false, false, 0L))
        assertFalse(FakeChatPolicy.shouldShowFake(true, true, true, false, 100L))
        assertFalse(FakeChatPolicy.shouldShowFake(true, true, true, true, 0L))
        assertFalse(FakeChatPolicy.shouldShowFake(true, true, true, true, -5L))
        assertTrue(FakeChatPolicy.shouldShowFake(true, true, true, true, 1L))
        // isPinValid: 4-12 chars, all decimal digits (Nd). Arabic-Indic
        // and fullwidth digits count; ASCII letters do not.
        assertTrue(FakeChatPolicy.isPinValid("1234"))
        assertTrue(FakeChatPolicy.isPinValid("123456789012"))
        assertFalse(FakeChatPolicy.isPinValid("123"))
        assertFalse(FakeChatPolicy.isPinValid("1234567890123"))
        assertFalse(FakeChatPolicy.isPinValid("12a4"))
        assertTrue(FakeChatPolicy.isPinValid("١٢٣٤"))
        assertTrue(FakeChatPolicy.isPinValid("１２３４"))
        assertFalse(FakeChatPolicy.isPinValid(""))
        // isWeakPin: only the two default PINs are weak.
        assertTrue(FakeChatPolicy.isWeakPin("0000"))
        assertTrue(FakeChatPolicy.isWeakPin("1234"))
        assertFalse(FakeChatPolicy.isWeakPin("0001"))
        assertFalse(FakeChatPolicy.isWeakPin(""))
        // verifyPinHash: stored strings are iterations:b64(salt):
        // b64(hash) with salt bytes 01 02 03 04 (literals computed by
        // the Python oracle and cross-checked against wsprobe128.log).
        val stored1 = "1:AQIDBA==:20y4sD9xh8ZdIut95jM97RMczftxvrwodGUP4PRLYVU="
        assertTrue(FakeChatPolicy.verifyPinHash("1234", stored1))
        assertFalse(FakeChatPolicy.verifyPinHash("9999", stored1))
        val stored100 = "100:AQIDBA==:T0IM96MUkV65BxXmv//LtMbFmAwqbh+OVFyMqLZnPfo="
        assertTrue(FakeChatPolicy.verifyPinHash("1234", stored100))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "2:AQIDBA==:XgbMC9gUcjtENIN3eoSID0WW+H8TzsqXoMUWKe6ZR1U="))
        // Malformed stored strings fail closed instead of throwing.
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "a:b"))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "a:b:c:d"))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "abc:AA==:AA=="))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "1:!!!:AA=="))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "1:AA==:!!!"))
        assertFalse(FakeChatPolicy.verifyPinHash("1234", "1:AA==:"))
        // Zero/negative iterations and an empty salt reach PBEKeySpec
        // and throw instead of failing closed.
        assertFailsWith<IllegalArgumentException> { FakeChatPolicy.verifyPinHash("1234", "0:AA==:AA==") }
        assertFailsWith<IllegalArgumentException> { FakeChatPolicy.verifyPinHash("1234", "-1:AA==:AA==") }
        assertFailsWith<IllegalArgumentException> { FakeChatPolicy.verifyPinHash("1234", "1::AA==") }
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle128.genFcp consumption order exactly, so seed
        // 128001 replays the same 1500 GEN lines drive128.py ran.
        val rnd = Random(128001)
        repeat(1500) {
            when (rnd.nextInt(4)) {
                0 -> {
                    val e = genFcpBool(rnd)
                    val h = genFcpBool(rnd)
                    val u = genFcpBool(rnd)
                    val r = genFcpBool(rnd)
                    val bg = genFcpBgAt(rnd)
                    assertEquals(fcpShowOracle(e, h, u, r, bg), FakeChatPolicy.shouldShowFake(e, h, u, r, bg))
                }
                1 -> {
                    val pin = genFcpPin(rnd)
                    assertEquals(fcpValidOracle(pin), FakeChatPolicy.isPinValid(pin))
                }
                2 -> {
                    val pin = genFcpPin(rnd)
                    assertEquals(fcpWeakOracle(pin), FakeChatPolicy.isWeakPin(pin))
                }
                else -> {
                    val pin = genFcpPin(rnd)
                    val stored = genFcpStored(rnd, pin)
                    // verifyPinHash throws (PBEKeySpec) for an empty salt
                    // or non-positive iterations; the oracle throws the
                    // same way, so compare "THROW" with the boolean.
                    val want = try { fcpVerifyOracle(pin, stored).toString() } catch (e: IllegalArgumentException) { "THROW" }
                    val got = try { FakeChatPolicy.verifyPinHash(pin, stored).toString() } catch (e: IllegalArgumentException) { "THROW" }
                    assertEquals(want, got)
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // The show matrix, the pin pool, whitespace/C0/Nd singles, and
        // the verify matrix -- the same inputs drive128.py sweeps.
        for (e in listOf(false, true)) {
            for (h in listOf(false, true)) {
                for (u in listOf(false, true)) {
                    for (r in listOf(false, true)) {
                        for (bg in FCP_BG_POOL) {
                            assertEquals(fcpShowOracle(e, h, u, r, bg), FakeChatPolicy.shouldShowFake(e, h, u, r, bg))
                        }
                    }
                }
            }
        }
        for (pin in FCP_PIN_POOL) {
            assertEquals(fcpValidOracle(pin), FakeChatPolicy.isPinValid(pin))
            assertEquals(fcpWeakOracle(pin), FakeChatPolicy.isWeakPin(pin))
        }
        val wsUnits = arrayOf(
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            0xA0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
            0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000
        )
        for (u in wsUnits) {
            val c = u.toChar().toString()
            assertEquals(fcpValidOracle(c), FakeChatPolicy.isPinValid(c))
            assertEquals(fcpValidOracle("123$c"), FakeChatPolicy.isPinValid("123$c"))
            assertEquals(fcpValidOracle(c + "1234"), FakeChatPolicy.isPinValid(c + "1234"))
        }
        for (u in 0x00..0x1F) {
            val pin = u.toChar() + "1234"
            assertEquals(fcpValidOracle(pin), FakeChatPolicy.isPinValid(pin))
        }
        val ndSamples = intArrayOf(
            0x30, 0x39, 0x660, 0x669, 0x6F0, 0x966, 0x9E6, 0xA66, 0xAE6,
            0xB66, 0xBE6, 0xC66, 0xCE6, 0xD66, 0xE50, 0xED0, 0xF20, 0x1040,
            0x17E0, 0x1810, 0xFF10, 0xFF19, 0x104A0, 0x1D7CE, 0x1D7D8, 0x1E140
        )
        for (u in ndSamples) {
            val c = u.toChar().toString()
            assertEquals(fcpValidOracle("123$c"), FakeChatPolicy.isPinValid("123$c"))
            assertEquals(fcpValidOracle(c.repeat(4)), FakeChatPolicy.isPinValid(c.repeat(4)))
        }
        for (pin in arrayOf("123\uD83D\uDE00", "\uD83D\uDE001234", "1234\uD800", "\uD8001234",
            "12\uDC0034", "١٢٣٤", "１２３４", "१२३४", "๑๒๓๔")) {
            assertEquals(fcpValidOracle(pin), FakeChatPolicy.isPinValid(pin))
        }
        val verifyPins = arrayOf("1234", "0000", "pässwörd", "a\uD800b", "\uDC00", "hello", "", "9999")
        val srnd = Random(128001)
        val enc = Base64.getEncoder()
        for (pin in verifyPins) {
            for (iters in FCP_ITER_POOL) {
                val salt = ByteArray(srnd.nextInt(1, 17)) { srnd.nextInt(256).toByte() }
                val hash = pbkdf2Oracle(pin, salt, iters)
                val good = "$iters:${enc.encodeToString(salt)}:${enc.encodeToString(hash)}"
                assertEquals(fcpVerifyOracle(pin, good), FakeChatPolicy.verifyPinHash(pin, good))
                val other = if (pin != "9999") "9999" else "1234"
                assertEquals(fcpVerifyOracle(other, good), FakeChatPolicy.verifyPinHash(other, good))
                val badHash = hash.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
                val bad = "$iters:${enc.encodeToString(salt)}:${enc.encodeToString(badHash)}"
                assertEquals(fcpVerifyOracle(pin, bad), FakeChatPolicy.verifyPinHash(pin, bad))
            }
            for (ms in arrayOf("a:b", "a:b:c:d", "abc:AA==:AA==", ":AA==:AA==", "1:!!!:AA==",
                "1:AA==:!!!", "1::AA==", "1:AA==:", "1:é:AA==", "0:AA==:AA==",
                "-1:AA==:AA==", " 1:AA==:AA==", "1 :AA==:AA==",
                "99999999999999999999:AA==:AA==", "2147483647:!!!:AA==",
                "2147483648:AA==:AA==", "+1:AA==:AA==", "01:AA==:AA==")) {
                // "0:…", "-1:…" and "1::…" throw (see pinnedBoundaries);
                // the rest fail closed.
                if (ms == "0:AA==:AA==" || ms == "-1:AA==:AA==" || ms == "1::AA==") continue
                assertEquals(fcpVerifyOracle(pin, ms), FakeChatPolicy.verifyPinHash(pin, ms))
            }
        }
        // Two deliberate big-iteration cases (the production default is
        // 600_000; each takes well under a second here).
        for (iters in listOf(600000, 1000000)) {
            val salt = byteArrayOf(1, 2, 3, 4)
            val hash = pbkdf2Oracle("1234", salt, iters)
            val stored = "$iters:${enc.encodeToString(salt)}:${enc.encodeToString(hash)}"
            assertEquals(fcpVerifyOracle("1234", stored), FakeChatPolicy.verifyPinHash("1234", stored))
            assertEquals(fcpVerifyOracle("9999", stored), FakeChatPolicy.verifyPinHash("9999", stored))
        }
    }
}
