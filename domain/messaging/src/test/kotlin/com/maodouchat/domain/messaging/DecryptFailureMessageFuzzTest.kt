package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.DecryptResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------- oracle ----------

// decryptFailureMessage restated by hand: each failure variant has
// its own code; Success and Duplicate are not failures and throw.
internal fun decryptFailureMessageOracle173(result: DecryptResult): String = when (result) {
    DecryptResult.NoSession -> "messaging_v2_no_session"
    DecryptResult.UntrustedIdentity -> "messaging_v2_untrusted_identity"
    DecryptResult.FutureEpoch -> "messaging_v2_future_group_revision"
    DecryptResult.NotForThisDevice -> "messaging_v2_wrong_device"
    DecryptResult.UnsupportedEnvelope -> "messaging_v2_unsupported_ciphertext"
    DecryptResult.Failed -> "messaging_v2_decrypt_failed"
    is DecryptResult.Success, DecryptResult.Duplicate ->
        error("decryptFailureMessage: $result is not a failure")
}

// Production decryptFailureMessage returns the failure code.
internal fun decryptFailureMessageProduction173(result: DecryptResult): String =
    decryptFailureMessage(result)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonFail173(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

// One result per kind tag; the plaintext only matters for Success.
internal fun buildResult173(kind: String, plaintext: String): DecryptResult = when (kind) {
    "Success" -> DecryptResult.Success(plaintext)
    "UnsupportedEnvelope" -> DecryptResult.UnsupportedEnvelope
    "NotForThisDevice" -> DecryptResult.NotForThisDevice
    "NoSession" -> DecryptResult.NoSession
    "UntrustedIdentity" -> DecryptResult.UntrustedIdentity
    "FutureEpoch" -> DecryptResult.FutureEpoch
    "Failed" -> DecryptResult.Failed
    else -> DecryptResult.Duplicate
}

internal data class FailCase173(
    val kind: String,
    val plaintext: String
)

internal val FAIL_KIND_POOL173: List<String> = listOf(
    "Success", "UnsupportedEnvelope", "NotForThisDevice", "NoSession",
    "UntrustedIdentity", "FutureEpoch", "Failed", "Duplicate"
)

internal val FAIL_PLAINTEXT_POOL173: List<String> = listOf(
    "", "plain", " ", "\t", "a\nb", "a\rb", "quote\"x", "back\\slash",
    "ünïcode✓", "🎉", "x".repeat(100), "\u0001\u0002",
    "Success(plaintext=plain)", "messaging_v2_no_session", "  padded  ",
    "\u0000", "a:b|c^d", "line1\nline2\ttab"
)

internal fun randomFailCase173(rnd: Random): FailCase173 =
    FailCase173(
        kind = FAIL_KIND_POOL173[rnd.nextInt(FAIL_KIND_POOL173.size)],
        plaintext = FAIL_PLAINTEXT_POOL173[rnd.nextInt(FAIL_PLAINTEXT_POOL173.size)]
    )

internal fun failWireJson173(c: FailCase173): String =
    "{\"kind\":${escJsonFail173(c.kind)},\"plaintext\":${escJsonFail173(c.plaintext)}}"

class DecryptFailureMessageFuzzTest {

    // Oracle and production must agree on the value, or on the
    // exception class and message when both throw.
    private fun checkFail173(result: DecryptResult) {
        val oracleOutcome = runCatching { decryptFailureMessageOracle173(result) }
        val prodOutcome = runCatching { decryptFailureMessageProduction173(result) }
        assertEquals(oracleOutcome.isSuccess, prodOutcome.isSuccess, "success($result)")
        if (oracleOutcome.isSuccess) {
            assertEquals(oracleOutcome.getOrThrow(), prodOutcome.getOrThrow(), "value($result)")
        } else {
            val o = oracleOutcome.exceptionOrNull()!!
            val p = prodOutcome.exceptionOrNull()!!
            assertEquals(o.javaClass, p.javaClass, "throw-class($result)")
            assertEquals(o.message, p.message, "throw-message($result)")
        }
    }

    @Test
    fun pinnedBoundaries() {
        val codes = mapOf(
            DecryptResult.NoSession to "messaging_v2_no_session",
            DecryptResult.UntrustedIdentity to "messaging_v2_untrusted_identity",
            DecryptResult.FutureEpoch to "messaging_v2_future_group_revision",
            DecryptResult.NotForThisDevice to "messaging_v2_wrong_device",
            DecryptResult.UnsupportedEnvelope to "messaging_v2_unsupported_ciphertext",
            DecryptResult.Failed to "messaging_v2_decrypt_failed"
        )
        // The six codes must be pairwise distinct: the inbox syncer
        // tells retry apart from dead-letter by them.
        assertEquals(6, codes.values.toSet().size)
        for ((result, expected) in codes) {
            assertEquals(expected, decryptFailureMessageOracle173(result), "oracle($result)")
            assertEquals(expected, decryptFailureMessageProduction173(result), "production($result)")
        }
        // Success and Duplicate throw; the message names the result.
        for (result in listOf(DecryptResult.Success("plain"), DecryptResult.Duplicate)) {
            checkFail173(result)
        }
        val thrown = assertFailsWith<IllegalStateException> {
            decryptFailureMessageProduction173(DecryptResult.Success("plain"))
        }
        assertEquals(
            "decryptFailureMessage: Success(plaintext=plain) is not a failure",
            thrown.message
        )
        val dupThrown = assertFailsWith<IllegalStateException> {
            decryptFailureMessageProduction173(DecryptResult.Duplicate)
        }
        assertEquals("decryptFailureMessage: Duplicate is not a failure", dupThrown.message)
        // The throw message carries the plaintext verbatim.
        val weird = assertFailsWith<IllegalStateException> {
            decryptFailureMessageProduction173(DecryptResult.Success("a\nb\"c"))
        }
        assertEquals(
            "decryptFailureMessage: Success(plaintext=a\nb\"c) is not a failure",
            weird.message
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(173001)
        repeat(4000) {
            val c = randomFailCase173(rnd)
            checkFail173(buildResult173(c.kind, c.plaintext))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every variant crossed with every plaintext in the pool:
        // the plaintext only shows up inside the throw message.
        for (kind in FAIL_KIND_POOL173) {
            for (plaintext in FAIL_PLAINTEXT_POOL173) {
                checkFail173(buildResult173(kind, plaintext))
            }
        }
    }
}
