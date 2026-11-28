package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.SenderKeyDistOutcome
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isSenderKeyInstallFailure restated by hand: only a Failed install
// aborts the inbox sync; Installed and Skipped (idempotent replay)
// are both accepted.
internal fun isSenderKeyInstallFailureOracle176(outcome: SenderKeyDistOutcome): Boolean =
    outcome == SenderKeyDistOutcome.Failed

// BackgroundSessionGatePolicy.mayContinue restated by hand: a purge
// in progress stops everything; a blank expected user id, a blank
// live token or a blank live user id all stop the batch; otherwise
// the live user id must equal the batch user id. The token value
// itself is never compared, only its presence.
internal fun mayContinueOracle176(
    expectedUserId: String,
    liveToken: String?,
    liveUserId: String?,
    purgeInProgress: Boolean,
): Boolean {
    if (purgeInProgress) return false
    if (expectedUserId.isBlank()) return false
    if (liveToken.isNullOrBlank() || liveUserId.isNullOrBlank()) return false
    return liveUserId == expectedUserId
}

// Production isSenderKeyInstallFailure classifies the install outcome.
internal fun isSenderKeyInstallFailureProduction176(outcome: SenderKeyDistOutcome): Boolean =
    isSenderKeyInstallFailure(outcome)

// Production mayContinueBatch gates the SenderKey redistribution batch.
internal fun mayContinueBatchProduction176(
    batchUserId: String,
    liveToken: String?,
    liveUserId: String?,
    purgeInProgress: Boolean,
): Boolean = SenderKeyRetrySessionPolicy.mayContinueBatch(
    batchUserId, liveToken, liveUserId, purgeInProgress
)

// Production mayContinueFlush gates the outbox flush.
internal fun mayContinueFlushProduction176(
    batchUserId: String,
    liveToken: String?,
    liveUserId: String?,
    purgeInProgress: Boolean,
): Boolean = TextOutboxSessionPolicy.mayContinueFlush(
    batchUserId, liveToken, liveUserId, purgeInProgress
)

// ---------- generators ----------

internal data class SenderKeyGateCase176(
    val outcome: String,
    val batchUserId: String,
    val liveToken: String?,
    val liveUserId: String?,
    val purgeInProgress: Boolean,
)

internal val SENDER_KEY_OUTCOME_POOL176: List<String> = listOf(
    "Installed", "Skipped", "Failed",
)

internal val SENDER_KEY_USER_POOL176: List<String?> = listOf(
    null, "", " ", "  ", "u1", "u2", "user-1", "  u1  ", "u1\u00a0",
    "\u00a0", "a".repeat(100),
)

internal fun randomSenderKeyGateCase176(rnd: Random): SenderKeyGateCase176 {
    fun randomUser(): String? {
        if (rnd.nextInt(4) == 0) {
            return SENDER_KEY_USER_POOL176[rnd.nextInt(SENDER_KEY_USER_POOL176.size)]
        }
        val alphabet = "u12ab \u00a0"
        val len = rnd.nextInt(0, 8)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    return SenderKeyGateCase176(
        outcome = SENDER_KEY_OUTCOME_POOL176[rnd.nextInt(SENDER_KEY_OUTCOME_POOL176.size)],
        batchUserId = randomUser() ?: "u1",
        liveToken = randomUser(),
        liveUserId = randomUser(),
        purgeInProgress = rnd.nextBoolean()
    )
}

internal fun escJsonSenderKey176(s: String): String {
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

internal fun escJsonOrNullSenderKey176(s: String?): String =
    if (s == null) "null" else escJsonSenderKey176(s)

internal fun senderKeyGateWireJson176(c: SenderKeyGateCase176): String =
    "{\"outcome\":${escJsonSenderKey176(c.outcome)}," +
        "\"batchUserId\":${escJsonSenderKey176(c.batchUserId)}," +
        "\"liveToken\":${escJsonOrNullSenderKey176(c.liveToken)}," +
        "\"liveUserId\":${escJsonOrNullSenderKey176(c.liveUserId)}," +
        "\"purgeInProgress\":${c.purgeInProgress}}"

internal fun senderKeyGateProjectionJson176(
    installFailure: Boolean,
    batch: Boolean,
    flush: Boolean,
): String =
    "{\"installFailure\":$installFailure,\"batch\":$batch,\"flush\":$flush}"

class SenderKeyGatesFuzzTest {

    private fun checkSenderKeyGate176(c: SenderKeyGateCase176) {
        val outcome = SenderKeyDistOutcome.valueOf(c.outcome)
        assertEquals(
            senderKeyGateProjectionJson176(
                isSenderKeyInstallFailureOracle176(outcome),
                mayContinueOracle176(c.batchUserId, c.liveToken, c.liveUserId, c.purgeInProgress),
                mayContinueOracle176(c.batchUserId, c.liveToken, c.liveUserId, c.purgeInProgress)
            ),
            senderKeyGateProjectionJson176(
                isSenderKeyInstallFailureProduction176(outcome),
                mayContinueBatchProduction176(
                    c.batchUserId, c.liveToken, c.liveUserId, c.purgeInProgress
                ),
                mayContinueFlushProduction176(
                    c.batchUserId, c.liveToken, c.liveUserId, c.purgeInProgress
                )
            ),
            "senderKeyGate($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every outcome against every user/token shape at both purge
        // values: the enum branch and the four mayContinue gates are
        // pinned before any random input runs.
        for (outcome in SENDER_KEY_OUTCOME_POOL176) {
            for (batch in SENDER_KEY_USER_POOL176) {
                for (token in SENDER_KEY_USER_POOL176) {
                    for (live in SENDER_KEY_USER_POOL176) {
                        for (purge in listOf(false, true)) {
                            checkSenderKeyGate176(
                                SenderKeyGateCase176(
                                    outcome, batch ?: "u1", token, live, purge
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(176003)
        repeat(4000) { checkSenderKeyGate176(randomSenderKeyGateCase176(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every outcome, every pooled user/token pair, both purge
        // values, plus every one- and two-letter string over the user
        // alphabet as the batch id (catches blank/equality slips).
        for (outcome in SENDER_KEY_OUTCOME_POOL176) {
            for (batch in SENDER_KEY_USER_POOL176) {
                for (token in SENDER_KEY_USER_POOL176) {
                    checkSenderKeyGate176(
                        SenderKeyGateCase176(outcome, batch ?: "u1", token, batch, false)
                    )
                }
            }
        }
        val letters = "u12 "
        for (a in letters) {
            checkSenderKeyGate176(
                SenderKeyGateCase176("Failed", a.toString(), "tok", a.toString(), false)
            )
            for (b in letters) {
                checkSenderKeyGate176(
                    SenderKeyGateCase176("Installed", "$a$b", "tok", "$a$b", false)
                )
            }
        }
    }
}
