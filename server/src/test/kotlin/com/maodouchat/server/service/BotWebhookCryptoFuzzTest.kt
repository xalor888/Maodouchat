package com.maodouchat.server.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------- oracle ----------

// Independent re-implementation of the two crypto helpers. The hex loop
// uses the masked form; the format probe (fuzz-190) showed the raw
// "%02x".format(Byte) form production uses renders identically on this
// JDK (high bytes stay two hex digits), so the two must agree on every
// token whose digest contains a high byte -- which is nearly all of them.
private fun sha256HexOracle190(data: ByteArray): String {
    val dig = MessageDigest.getInstance("SHA-256").digest(data)
    return dig.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun hmacSha256HexOracle190(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    return raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun hashTokenLikeOracle190(token: String): String =
    sha256HexOracle190(token.trim().toByteArray(StandardCharsets.UTF_8))

private val SECRET_POOL190 = listOf(
    "key", "secret", "s3cr3t!", "a".repeat(64), "密钥", "İ",
    "a\uD800b", "x".repeat(200), " ", "k e y", "key\n", "0"
)
private val MESSAGE_POOL190 = listOf(
    "", "hello", "The quick brown fox jumps over the lazy dog", "{}",
    "{\"a\":1}", "a".repeat(1000), "multi\nline\ttext", "émoji 😀",
    "a\uD800b", " ", "0", "x".repeat(64)
)
private val TOKEN_POOL190 = listOf(
    "", " ", "   ", "probe-token", "  probe-token  ",
    "\u00A0probe-token\u00A0", "token123", "tok_en-ABC_123",
    "x".repeat(128), "密钥token", "a\uD800b", "😀", "\t token \n"
)

private const val CRYPTO_ALPHABET190 =
    "abcXYZ019 _-!@#{}:,. \t\n\u00A0密钥é😀İΣ"

private fun randomSecret190(r: Random): String {
    if (r.nextInt(4) == 0) return SECRET_POOL190.random(r)
    val n = r.nextInt(1, 65)
    return buildString {
        repeat(n) { append(CRYPTO_ALPHABET190.random(r)) }
    }
}

private fun randomMessage190(r: Random): String {
    if (r.nextInt(4) == 0) return MESSAGE_POOL190.random(r)
    val n = r.nextInt(0, 120)
    return buildString {
        repeat(n) { append(CRYPTO_ALPHABET190.random(r)) }
    }
}

private fun randomToken190(r: Random): String {
    if (r.nextInt(4) == 0) return TOKEN_POOL190.random(r)
    val n = r.nextInt(0, 65)
    return buildString {
        repeat(n) { append(CRYPTO_ALPHABET190.random(r)) }
    }
}

// ---------- tests ----------

class BotWebhookCryptoFuzzTest {

    @Test
    fun hmacSha256Differential190() {
        val r = Random(190003)
        repeat(250) {
            val secret = randomSecret190(r)
            val message = randomMessage190(r)
            assertEquals(
                hmacSha256HexOracle190(secret, message),
                BotWebhookService.hmacSha256Hex(secret, message)
            )
        }
    }

    @Test
    fun hashTokenLikeDifferential190() {
        val r = Random(190004)
        repeat(250) {
            val token = randomToken190(r)
            assertEquals(
                hashTokenLikeOracle190(token),
                BotWebhookService.hashTokenLike(token)
            )
        }
    }

    @Test
    fun botWebhookCryptoPinned190() {
        // Known answers, computed independently with Python hashlib.
        // (Empty secret has no known answer on the JVM: Mac.init throws,
        // see the assertFailsWith checks below; Python hmac allows it.)
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            BotWebhookService.hmacSha256Hex(
                "key", "The quick brown fox jumps over the lazy dog"
            )
        )
        assertEquals(
            "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b",
            BotWebhookService.hmacSha256Hex("secret", "hello")
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BotWebhookService.hashTokenLike("")
        )
        assertEquals(
            "92e2a3caaa3bec34d48004c7da4036f21c00828f51788c7650b9ee4ba5ef63d5",
            BotWebhookService.hashTokenLike("probe-token")
        )
        assertEquals(
            "92e2a3caaa3bec34d48004c7da4036f21c00828f51788c7650b9ee4ba5ef63d5",
            BotWebhookService.hashTokenLike("  probe-token  ")
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BotWebhookService.hashTokenLike("   ")
        )
        // Empty secret throws in production (Mac.init: Empty key); the
        // oracle throws the same way, so both sides agree on the throw.
        assertFailsWith<IllegalArgumentException> {
            BotWebhookService.hmacSha256Hex("", "x")
        }
        assertFailsWith<IllegalArgumentException> {
            hmacSha256HexOracle190("", "x")
        }
        // Whitespace-only difference around a token must not change the hash.
        assertEquals(
            BotWebhookService.hashTokenLike("probe-token"),
            BotWebhookService.hashTokenLike("\u00A0probe-token\u00A0")
        )
        // Lone surrogates encode as '?' on both sides.
        assertEquals(
            hashTokenLikeOracle190("a\uD800b"),
            BotWebhookService.hashTokenLike("a\uD800b")
        )
        assertEquals(
            hmacSha256HexOracle190("k\uD800", "m\uD800"),
            BotWebhookService.hmacSha256Hex("k\uD800", "m\uD800")
        )
    }
}
