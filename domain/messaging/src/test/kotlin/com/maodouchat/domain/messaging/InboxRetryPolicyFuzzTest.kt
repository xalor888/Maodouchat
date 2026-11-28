// Fuzz / random-oracle tests for InboxRetryPolicy
// (checklist line 931, 157th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the error code
// is the exception message cut to 120 UTF-16 units (blank or missing
// falls back to the exception class name); a permanent code or the 8th
// failure dead-letters; otherwise the retry lands at now + an
// exponential backoff (1s << attempts-1, capped at 300s, exponent
// clamped to 12, the sum wrapping near Long.MAX).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal val INBOX_ERROR_CLASSES157 = mapOf(
    "ISE" to "IllegalStateException",
    "IAE" to "IllegalArgumentException",
    "RTE" to "RuntimeException",
    "EX" to "Exception",
    "API" to "ApiException",
)

internal val INBOX_PERMANENT157 = setOf(
    "messaging_v2_wrong_device",
    "messaging_v2_unsupported_ciphertext",
    "messaging_v2_sender_key_install_failed",
)

internal fun inboxErrorCodeOracle157(kind: String, message: String?): String =
    message?.take(120)?.ifBlank { null } ?: INBOX_ERROR_CLASSES157.getValue(kind)

internal fun inboxNextAttemptAtOracle157(now: Long, attempts: Int): Long {
    val exponent = (attempts - 1).coerceIn(0, 12)
    val delay = minOf(300_000L, 1_000L shl exponent)
    return now + delay
}

internal fun inboxDecideOracle157(kind: String, message: String?, attempts: Int, now: Long): String {
    val errorCode = inboxErrorCodeOracle157(kind, message)
    return if (errorCode in INBOX_PERMANENT157 || attempts >= 8) {
        "D:$errorCode"
    } else {
        "R:$errorCode:${inboxNextAttemptAtOracle157(now, attempts)}"
    }
}

// ---------- generators ----------

internal val INBOX_MESSAGE_POOL157 = listOf(
    null, "", " ", "   ", "x", "boom", "transient_ratchet_failure",
    "messaging_v2_wrong_device",
    "messaging_v2_unsupported_ciphertext",
    "messaging_v2_sender_key_install_failed",
    "messaging_v2_wrong_device" + "x".repeat(200),
    "sender_key_coverage_pending",
    "x".repeat(119), "x".repeat(120), "x".repeat(121),
    " ".repeat(119) + "x", " ".repeat(120), " ".repeat(120) + "x",
    "\u00a0", "\u0085", "\t\n", "é".repeat(200),
    "\uD83D\uDE00".repeat(100),
)

internal val INBOX_KIND_POOL157 = listOf("ISE", "IAE", "RTE", "EX", "API")

internal val INBOX_SERVER_CODE_POOL157 = listOf(
    null, "", "DEVICE_COVERAGE_MISMATCH", "GROUP_REVISION_MISMATCH",
    "RATE_LIMITED", "x",
)

internal val INBOX_ATTEMPTS_POOL157 = listOf(
    Int.MIN_VALUE, -1, 0, 1, 2, 7, 8, 9, 12, 13, 100, Int.MAX_VALUE,
)

internal val INBOX_NOW_POOL157 = listOf(
    Long.MIN_VALUE, -1L, 0L, 1L, 10_000L,
    Long.MAX_VALUE - 300_000, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val INBOX_ALPHABET157 = "abcXYZ019 \t\u00a0\u0085é\u4e2d\uD83D\uDE00x"

internal data class InboxCase157(
    val kind: String,
    val message: String?,
    val serverCode: String?,
    val attempts: Int,
    val now: Long,
)

internal fun randomInboxMessage157(rnd: Random): String? = when (rnd.nextInt(10)) {
    0 -> null
    1 -> INBOX_MESSAGE_POOL157[rnd.nextInt(INBOX_MESSAGE_POOL157.size)]
    else -> {
        val lengthUnits = rnd.nextInt(0, 260)
        val sb = StringBuilder()
        var units = 0
        while (units < lengthUnits) {
            val c = INBOX_ALPHABET157[rnd.nextInt(INBOX_ALPHABET157.length)]
            sb.append(c)
            units += 1
        }
        sb.toString()
    }
}

internal fun randomInboxCase157(rnd: Random): InboxCase157 {
    val kind = INBOX_KIND_POOL157[rnd.nextInt(INBOX_KIND_POOL157.size)]
    val message = randomInboxMessage157(rnd)
    val serverCode = if (kind == "API") {
        if (rnd.nextInt(10) < 7) INBOX_SERVER_CODE_POOL157[rnd.nextInt(INBOX_SERVER_CODE_POOL157.size)] else null
    } else {
        null
    }
    val attempts = when (rnd.nextInt(10)) {
        0 -> INBOX_ATTEMPTS_POOL157[rnd.nextInt(INBOX_ATTEMPTS_POOL157.size)]
        1 -> rnd.nextInt(-5, 20)
        else -> rnd.nextInt()
    }
    val now = when (rnd.nextInt(10)) {
        0 -> INBOX_NOW_POOL157[rnd.nextInt(INBOX_NOW_POOL157.size)]
        1 -> rnd.nextLong(-1_000_000, 1_000_001)
        else -> rnd.nextLong()
    }
    return InboxCase157(kind, message, serverCode, attempts, now)
}

internal fun inboxHex157(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

internal fun inboxWire157(c: InboxCase157): String =
    c.kind + "|" + inboxHex157(c.message ?: "") + "|" + inboxHex157(c.serverCode ?: "") +
        "|" + c.attempts + "|" + c.now

class InboxRetryPolicyFuzzTest {

    private fun check(c: InboxCase157) {
        assertEquals(
            inboxDecideOracle157(c.kind, c.message, c.attempts, c.now),
            inboxDecisionCode157(
                InboxRetryPolicy.decide(buildInboxError157(c.kind, c.message, c.serverCode), c.attempts, c.now),
            ),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // (kind, message, attempts, now, expected "D:code" / "R:code:next")
        val cases = listOf(
            InboxPin("ISE", "messaging_v2_wrong_device", 1, 10_000L, "D:messaging_v2_wrong_device"),
            InboxPin("ISE", "messaging_v2_unsupported_ciphertext", 1, 10_000L, "D:messaging_v2_unsupported_ciphertext"),
            InboxPin("ISE", "messaging_v2_sender_key_install_failed", 1, 10_000L, "D:messaging_v2_sender_key_install_failed"),
            InboxPin("ISE", "transient_ratchet_failure", 8, 10_000L, "D:transient_ratchet_failure"),
            InboxPin("ISE", "transient_ratchet_failure", 13, 10_000L, "D:transient_ratchet_failure"),
            InboxPin("ISE", "transient_ratchet_failure", 100, 10_000L, "D:transient_ratchet_failure"),
            InboxPin("ISE", "transient_ratchet_failure", 7, 10_000L, "R:transient_ratchet_failure:74000"),
            InboxPin("ISE", "transient_ratchet_failure", 1, 10_000L, "R:transient_ratchet_failure:11000"),
            InboxPin("ISE", "transient_ratchet_failure", 0, 10_000L, "R:transient_ratchet_failure:11000"),
            InboxPin("ISE", "transient_ratchet_failure", -5, 10_000L, "R:transient_ratchet_failure:11000"),
            // Blank or missing message falls back to the class name.
            InboxPin("ISE", null, 1, 10_000L, "R:IllegalStateException:11000"),
            InboxPin("ISE", "", 1, 10_000L, "R:IllegalStateException:11000"),
            InboxPin("ISE", "   ", 1, 10_000L, "R:IllegalStateException:11000"),
            InboxPin("IAE", null, 1, 10_000L, "R:IllegalArgumentException:11000"),
            InboxPin("API", null, 1, 10_000L, "R:ApiException:11000"),
            InboxPin("API", "messaging_v2_wrong_device", 1, 10_000L, "D:messaging_v2_wrong_device"),
            // take(120): a permanent code padded past 120 units no longer matches.
            InboxPin("ISE", "messaging_v2_wrong_device" + "x".repeat(200), 1, 10_000L, "R:" + "messaging_v2_wrong_device" + "x".repeat(95) + ":11000"),
            InboxPin("EX", "x".repeat(121), 1, 10_000L, "R:" + "x".repeat(120) + ":11000"),
            InboxPin("ISE", " ".repeat(120), 1, 10_000L, "R:IllegalStateException:11000"),
            InboxPin("ISE", " ".repeat(119) + "x", 1, 10_000L, "R:" + " ".repeat(119) + "x:11000"),
            // take(120) counts UTF-16 units: 100 emoji are 200 units, cut to 60.
            InboxPin("RTE", "\uD83D\uDE00".repeat(100), 1, 10_000L, "R:" + "\uD83D\uDE00".repeat(60) + ":11000"),
            // now + delay wraps near Long.MAX.
            InboxPin("ISE", "x", 1, Long.MAX_VALUE, "R:x:-9223372036854774809"),
            InboxPin("ISE", "x", 7, Long.MAX_VALUE, "R:x:-9223372036854711809"),
            InboxPin("ISE", "x", 14, Long.MAX_VALUE, "D:x"),
        )
        for (pin in cases) {
            assertEquals(pin.expected, inboxDecideOracle157(pin.kind, pin.message, pin.attempts, pin.now))
            check(InboxCase157(pin.kind, pin.message, null, pin.attempts, pin.now))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(157001)
        repeat(2000) { check(randomInboxCase157(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        for (kind in INBOX_KIND_POOL157) {
            for (message in INBOX_MESSAGE_POOL157) {
                for (attempts in listOf(0, 1, 7, 8, 13)) {
                    check(InboxCase157(kind, message, null, attempts, 10_000L))
                }
            }
        }
        for (attempts in -5..20) {
            for (now in INBOX_NOW_POOL157) {
                check(InboxCase157("ISE", "transient_ratchet_failure", null, attempts, now))
            }
        }
        // take(120) boundary: x-runs and space-runs around 120 units.
        for (n in 115..125) {
            check(InboxCase157("ISE", "x".repeat(n), null, 1, 10_000L))
            check(InboxCase157("ISE", " ".repeat(n), null, 1, 10_000L))
            check(InboxCase157("ISE", " ".repeat(n) + "x", null, 1, 10_000L))
        }
        // ApiException server codes never decide here.
        for (code in INBOX_SERVER_CODE_POOL157) {
            check(InboxCase157("API", "messaging_v2_wrong_device", code, 1, 10_000L))
            check(InboxCase157("API", "boom", code, 1, 10_000L))
        }
    }

    private data class InboxPin(
        val kind: String,
        val message: String?,
        val attempts: Int,
        val now: Long,
        val expected: String,
    )
}

internal fun buildInboxError157(kind: String, message: String?, serverCode: String?): Exception = when (kind) {
    "ISE" -> IllegalStateException(message)
    "IAE" -> IllegalArgumentException(message)
    "RTE" -> RuntimeException(message)
    "EX" -> Exception(message)
    "API" -> com.maodouchat.core.model.ApiException(
        kind = com.maodouchat.core.model.ApiFailureKind.HTTP,
        serverCode = serverCode,
        serverMessage = message,
    )
    else -> error("unknown kind $kind")
}

internal fun inboxDecisionCode157(d: InboxRetryPolicy.Decision): String = when (d) {
    is InboxRetryPolicy.Decision.DeadLetter -> "D:${d.errorCode}"
    is InboxRetryPolicy.Decision.Retry -> "R:${d.errorCode}:${d.nextAttemptAt}"
}
