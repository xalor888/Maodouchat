package com.maodouchat.core.session

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ---------- oracle ----------

// Independent PBKDF2-HMAC-SHA256: hand-rolled HMAC over MessageDigest,
// not SecretKeyFactory, so it shares no code path with production.
private fun sha256188(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

private fun hmacSha256188(key: ByteArray, data: ByteArray): ByteArray {
    val k = if (key.size > 64) sha256188(key) else key
    val padded = k.copyOf(64)
    val ipad = ByteArray(64) { i -> (padded[i].toInt() xor 0x36).toByte() }
    val opad = ByteArray(64) { i -> (padded[i].toInt() xor 0x5c).toByte() }
    return sha256188(opad + sha256188(ipad + data))
}

internal fun pbkdf2Oracle188(pin: String, saltHex: String, iterations: Int): String {
    val pw = pin.toByteArray(Charsets.UTF_8)
    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // PBEKeySpec/SecretKeyFactory reject an empty salt, so production
    // throws IllegalArgumentException here even though a hand-rolled
    // PBKDF2 would happily hash with it.
    require(salt.isNotEmpty()) { "salt must not be empty" }
    val block = ByteArray(salt.size + 4)
    salt.copyInto(block)
    block[salt.size] = 0
    block[salt.size + 1] = 0
    block[salt.size + 2] = 0
    block[salt.size + 3] = 1
    var u = hmacSha256188(pw, block)
    val t = u.copyOf()
    for (i in 2..iterations) {
        u = hmacSha256188(pw, u)
        for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
    }
    return t.joinToString("") { "%02x".format(it) }
}

internal fun encodeStoredHashOracle188(pin: String, saltHex: String, iterations: Int): StoredPinHash {
    val hash = pbkdf2Oracle188(pin, saltHex, iterations)
    val formatted = "pbkdf2\$$iterations\$$saltHex\$$hash"
    return StoredPinHash(
        formattedString = formatted,
        saltHex = saltHex,
        hashHex = hash,
        iterations = iterations,
        isPbkdf2 = true,
    )
}

internal data class PinCase188(val pin: String, val saltHex: String, val iterations: Int)

internal fun pinResultWireJson188(c: PinCase188): String {
    return try {
        val s = encodeStoredHashOracle188(c.pin, c.saltHex, c.iterations)
        "OK|" + s.formattedString + "|" + s.saltHex + "|" + s.hashHex + "|" + s.iterations + "|1"
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

internal fun pinProdResult188(c: PinCase188): String {
    return try {
        val s = PinSecurityPolicy.encodeStoredHash(c.pin, c.saltHex, c.iterations)
        "OK|" + s.formattedString + "|" + s.saltHex + "|" + s.hashHex + "|" + s.iterations + "|" + if (s.isPbkdf2) "1" else "0"
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

// ---------- generators ----------

internal val PIN_POOL188 = listOf(
    "", "0", "1", "1234", "5678", "0000", "999999999999", "1234567890123",
    "密码", "🔑", "a", "ab12", "12 34", " 1234 ", "١٢٣٤",
    "x".repeat(50), "9".repeat(100),
)
internal val SALT_POOL188 = listOf(
    "", "00", "ff", "FF", "AaBbCc", "abc", "00112233445566778899aabbccddeeff",
    "0".repeat(32), "f".repeat(33), "zz", "0g", "12 34", "ab-cd",
)

internal fun randomHex188(r: Random, len: Int): String {
    val digits = "0123456789abcdef"
    return buildString { repeat(len) { append(digits[r.nextInt(16)]) } }
}

internal fun randomPinCase188(r: Random): PinCase188 {
    val pin = when (r.nextInt(6)) {
        0 -> PIN_POOL188.random(r)
        1 -> (1..r.nextInt(0, 13)).joinToString("") { r.nextInt(10).toString() }
        2 -> buildString { repeat(r.nextInt(0, 12)) { append(('a'..'z').random(r)) } }
        3 -> buildString { repeat(r.nextInt(0, 6)) { append(listOf("密", "码", "🔑", "é", "ß").random(r)) } }
        4 -> "x".repeat(r.nextInt(0, 30))
        else -> r.nextInt(0, 100000).toString()
    }
    val saltHex = when (r.nextInt(5)) {
        0 -> SALT_POOL188.random(r)
        1 -> randomHex188(r, r.nextInt(0, 35))
        2 -> randomHex188(r, r.nextInt(0, 35)).uppercase()
        3 -> buildString { repeat(r.nextInt(0, 10)) { append("0123456789abcdefABCDEF".random(r)) } }
        else -> randomHex188(r, 32)
    }
    return PinCase188(pin, saltHex, listOf(1, 2, 3, 7, 31, 64).random(r))
}

internal val PIN_PINNED188: List<PinCase188> = listOf(
    PinCase188("5678", "00112233445566778899aabbccddeeff", 1000),
    PinCase188("5678", "00112233445566778899aabbccddeeff", 1),
    PinCase188("5678", "00112233445566778899aabbccddeeff", 2),
    PinCase188("", "00112233445566778899aabbccddeeff", 10),
    PinCase188("", "", 1),
    PinCase188("1234", "", 10),
    PinCase188("密码🔑", "00112233445566778899aabbccddeeff", 10),
    PinCase188("é", "ff", 7),
    PinCase188("5678", "abc", 10),
    PinCase188("5678", "AABBCC", 10),
    PinCase188("5678", "zz", 10),
    PinCase188("5678", "0g", 10),
    PinCase188("5678", "12 34", 10),
    PinCase188("x".repeat(100), "00".repeat(16), 31),
    PinCase188("000000000000", "f".repeat(33), 3),
)

class PinSecurityEncodeFuzzTest {

    @Test
    fun pinnedPinEncode188() {
        for (c in PIN_PINNED188) {
            assertEquals(pinResultWireJson188(c), pinProdResult188(c), "pinned $c")
        }
    }

    @Test
    fun randomPinEncode188() {
        val r = Random(42)
        repeat(600) {
            val c = randomPinCase188(r)
            assertEquals(pinResultWireJson188(c), pinProdResult188(c), "random $c")
            // Encoded values must verify back (cheap: same iteration count, no upgrade re-encode).
            // Invalid-hex salts throw in encodeStoredHash, so only round-trip the encodable ones.
            if (pinProdResult188(c).startsWith("OK|")) {
                val stored = PinSecurityPolicy.encodeStoredHash(c.pin, c.saltHex, c.iterations)
                val outcome = PinSecurityPolicy.verifyPin(c.pin, stored.formattedString, minDesiredIterations = c.iterations)
                assertTrue(outcome is VerificationOutcome.Success && !outcome.needsUpgrade, "round-trip $c")
            }
        }
    }

    @Test
    fun saltFormatAndRoundTrip188() {
        for (n in 0..20) {
            val salt = PinSecurityPolicy.generateSalt(n)
            assertEquals(2 * n, salt.length, "salt len $n")
            assertTrue(salt.all { it in '0'..'9' || it in 'a'..'f' }, "salt hex $n")
        }
        val a = PinSecurityPolicy.generateSalt()
        val b = PinSecurityPolicy.generateSalt()
        assertEquals(32, a.length)
        assertTrue(a != b, "salts differ")
        for (iter in listOf(1, 2, 5, 10, 100, 1000)) {
            val c = PinCase188("5678", "00112233445566778899aabbccddeeff", iter)
            assertEquals(pinResultWireJson188(c), pinProdResult188(c), "iter sweep $iter")
        }
        val saltRnd = Random(7)
        for (n in 0..20) {
            val c = PinCase188("9".repeat(n), randomHex188(saltRnd, 2 * n), 10)
            assertEquals(pinResultWireJson188(c), pinProdResult188(c), "pin len $n")
        }
        assertFailsWith<NumberFormatException> {
            PinSecurityPolicy.encodeStoredHash("5678", "zz", 10)
        }
    }
}
