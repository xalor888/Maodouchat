// Fuzz / random-oracle tests for OutboxRetryPolicy
// (checklist line 931, 157th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a failure in
// the prepare phase (or an ApiException whose server code says the
// coverage / group revision is stale) goes back to re-encrypt; anything
// else resends as-is. When the message is exactly
// "sender_key_coverage_pending" the retry is immediate (now); otherwise
// it takes the same exponential backoff as the inbox (1s << attempts-1,
// capped at 300s, exponent clamped to 12, the sum wrapping near
// Long.MAX). The error code is the message cut to 120 UTF-16 units,
// blank or missing falling back to the exception class name.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal val OUTBOX_ERROR_CLASSES157 = mapOf(
    "ISE" to "IllegalStateException",
    "IAE" to "IllegalArgumentException",
    "RTE" to "RuntimeException",
    "EX" to "Exception",
    "API" to "ApiException",
)

internal fun outboxErrorCodeOracle157(kind: String, message: String?): String =
    message?.take(120)?.ifBlank { null } ?: OUTBOX_ERROR_CLASSES157.getValue(kind)

internal fun outboxBackoffOracle157(now: Long, attempts: Int): Long {
    val exponent = (attempts - 1).coerceIn(0, 12)
    val delay = minOf(300_000L, 1_000L shl exponent)
    return now + delay
}

internal fun outboxDecideOracle157(
    kind: String,
    message: String?,
    serverCode: String?,
    failedWhilePreparing: Boolean,
    attempts: Int,
    now: Long,
): String {
    val target = if (failedWhilePreparing ||
        (kind == "API" && serverCode in setOf("DEVICE_COVERAGE_MISMATCH", "GROUP_REVISION_MISMATCH"))
    ) "E" else "S"
    val next = if (message == "sender_key_coverage_pending") now
        else outboxBackoffOracle157(now, attempts)
    return "$target:$next:${outboxErrorCodeOracle157(kind, message)}"
}

// ---------- generators ----------

internal val OUTBOX_MESSAGE_POOL157 = listOf(
    null, "", " ", "   ", "x", "boom",
    "sender_key_coverage_pending",
    "sender_key_coverage_pending" + "x".repeat(200),
    "x".repeat(119), "x".repeat(120), "x".repeat(121),
    " ".repeat(120), "\u00a0", "\u0085", "\t\n",
    "é".repeat(200), "\uD83D\uDE00".repeat(100),
)

internal val OUTBOX_KIND_POOL157 = listOf("ISE", "IAE", "RTE", "EX", "API")

internal val OUTBOX_SERVER_CODE_POOL157 = listOf(
    null, "", "DEVICE_COVERAGE_MISMATCH", "GROUP_REVISION_MISMATCH",
    "RATE_LIMITED", "x",
)

internal val OUTBOX_ATTEMPTS_POOL157 = listOf(
    Int.MIN_VALUE, -1, 0, 1, 2, 3, 7, 8, 12, 13, 100, Int.MAX_VALUE,
)

internal val OUTBOX_NOW_POOL157 = listOf(
    Long.MIN_VALUE, -1L, 0L, 1L, 10_000L,
    Long.MAX_VALUE - 300_000, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val OUTBOX_ALPHABET157 = "abcXYZ019 \t\u00a0\u0085é\u4e2d\uD83D\uDE00x"

internal data class OutboxCase157(
    val kind: String,
    val message: String?,
    val serverCode: String?,
    val failedWhilePreparing: Boolean,
    val attempts: Int,
    val now: Long,
)

internal fun randomOutboxMessage157(rnd: Random): String? = when (rnd.nextInt(10)) {
    0 -> null
    1 -> OUTBOX_MESSAGE_POOL157[rnd.nextInt(OUTBOX_MESSAGE_POOL157.size)]
    else -> {
        val lengthUnits = rnd.nextInt(0, 260)
        val sb = StringBuilder()
        var units = 0
        while (units < lengthUnits) {
            sb.append(OUTBOX_ALPHABET157[rnd.nextInt(OUTBOX_ALPHABET157.length)])
            units += 1
        }
        sb.toString()
    }
}

internal fun randomOutboxCase157(rnd: Random): OutboxCase157 {
    val kind = OUTBOX_KIND_POOL157[rnd.nextInt(OUTBOX_KIND_POOL157.size)]
    val message = randomOutboxMessage157(rnd)
    val serverCode = if (kind == "API") {
        if (rnd.nextInt(10) < 7) OUTBOX_SERVER_CODE_POOL157[rnd.nextInt(OUTBOX_SERVER_CODE_POOL157.size)] else null
    } else {
        null
    }
    val attempts = when (rnd.nextInt(10)) {
        0 -> OUTBOX_ATTEMPTS_POOL157[rnd.nextInt(OUTBOX_ATTEMPTS_POOL157.size)]
        1 -> rnd.nextInt(-5, 20)
        else -> rnd.nextInt()
    }
    val now = when (rnd.nextInt(10)) {
        0 -> OUTBOX_NOW_POOL157[rnd.nextInt(OUTBOX_NOW_POOL157.size)]
        1 -> rnd.nextLong(-1_000_000, 1_000_001)
        else -> rnd.nextLong()
    }
    return OutboxCase157(kind, message, serverCode, rnd.nextBoolean(), attempts, now)
}

internal fun outboxHex157(s: String): String {
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

internal fun outboxWire157(c: OutboxCase157): String =
    c.kind + "|" + outboxHex157(c.message ?: "") + "|" + outboxHex157(c.serverCode ?: "") +
        "|" + (if (c.failedWhilePreparing) "1" else "0") + "|" + c.attempts + "|" + c.now

class OutboxRetryPolicyFuzzTest {

    private fun check(c: OutboxCase157) {
        assertEquals(
            outboxDecideOracle157(c.kind, c.message, c.serverCode, c.failedWhilePreparing, c.attempts, c.now),
            outboxDecisionCode157(
                OutboxRetryPolicy.decide(buildOutboxError157(c.kind, c.message, c.serverCode), c.failedWhilePreparing, c.attempts, c.now),
            ),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // (kind, message, serverCode, preparing, attempts, now, expected)
        val cases = listOf(
            OutboxPin("ISE", "boom", null, true, 1, 10_000L, "E:11000:boom"),
            OutboxPin("API", null, "DEVICE_COVERAGE_MISMATCH", false, 2, 10_000L, "E:12000:ApiException"),
            OutboxPin("API", null, "GROUP_REVISION_MISMATCH", false, 1, 10_000L, "E:11000:ApiException"),
            OutboxPin("API", null, "RATE_LIMITED", false, 1, 10_000L, "S:11000:ApiException"),
            OutboxPin("API", null, "", false, 1, 10_000L, "S:11000:ApiException"),
            OutboxPin("ISE", "sender_key_coverage_pending", null, false, 3, 10_000L, "S:10000:sender_key_coverage_pending"),
            // The pending check uses the raw message, not the truncated code.
            OutboxPin("ISE", "sender_key_coverage_pending" + "x".repeat(200), null, false, 3, 10_000L, "S:14000:" + "sender_key_coverage_pending" + "x".repeat(93)),
            // Preparing wins over a plain error; pending wins over backoff.
            OutboxPin("API", "sender_key_coverage_pending", "DEVICE_COVERAGE_MISMATCH", false, 1, 10_000L, "E:10000:sender_key_coverage_pending"),
            OutboxPin("ISE", "   ", null, false, 1, 10_000L, "S:11000:IllegalStateException"),
            OutboxPin("ISE", null, null, false, 1, 10_000L, "S:11000:IllegalStateException"),
            OutboxPin("EX", "x".repeat(121), null, false, 1, 10_000L, "S:11000:" + "x".repeat(120)),
            OutboxPin("ISE", "x", null, false, 1, Long.MAX_VALUE, "S:-9223372036854774809:x"),
            OutboxPin("ISE", "x", null, true, 1, Long.MAX_VALUE, "E:-9223372036854774809:x"),
        )
        for (pin in cases) {
            assertEquals(
                pin.expected,
                outboxDecideOracle157(pin.kind, pin.message, pin.serverCode, pin.preparing, pin.attempts, pin.now),
            )
            check(OutboxCase157(pin.kind, pin.message, pin.serverCode, pin.preparing, pin.attempts, pin.now))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(157002)
        repeat(2000) { check(randomOutboxCase157(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        for (kind in OUTBOX_KIND_POOL157) {
            for (message in OUTBOX_MESSAGE_POOL157) {
                for (preparing in listOf(false, true)) {
                    check(OutboxCase157(kind, message, null, preparing, 1, 10_000L))
                }
            }
        }
        for (code in OUTBOX_SERVER_CODE_POOL157) {
            for (preparing in listOf(false, true)) {
                for (attempts in listOf(0, 1, 3, 13)) {
                    check(OutboxCase157("API", "boom", code, preparing, attempts, 10_000L))
                }
            }
        }
        for (attempts in -5..20) {
            for (now in OUTBOX_NOW_POOL157) {
                check(OutboxCase157("ISE", "boom", null, false, attempts, now))
                check(OutboxCase157("ISE", "sender_key_coverage_pending", null, false, attempts, now))
            }
        }
        for (n in 115..125) {
            check(OutboxCase157("ISE", "x".repeat(n), null, false, 1, 10_000L))
        }
    }

    private data class OutboxPin(
        val kind: String,
        val message: String?,
        val serverCode: String?,
        val preparing: Boolean,
        val attempts: Int,
        val now: Long,
        val expected: String,
    )
}

internal fun buildOutboxError157(kind: String, message: String?, serverCode: String?): Exception = when (kind) {
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

internal fun outboxDecisionCode157(d: OutboxRetryPolicy.Decision): String =
    (if (d.target == OutboxRetryPolicy.RetryTarget.RE_ENCRYPT) "E" else "S") +
        ":" + d.nextAttemptAt + ":" + d.errorCode
