package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Conversation privacy gating restated by hand. Ground truth, read
// from the production files:
//   - allows: SEARCH needs a non-secret, unlocked conversation; every
//     other action only needs a non-secret conversation.
//   - Secret chat machine: ACTIVE --read+timer--> ARMED, ARMED
//     --expiry--> EXPIRING, any state --destroy--> DESTROYED; all
//     other transitions leave the state alone.
//   - Forward evaluate: PIN_LOCKED > SECRET_CHAT > TERMINAL >
//     PRIVACY > ALLOWED, checked in that order.
internal fun allowsOracle182(secret: Boolean, locked: Boolean, action: String): Boolean =
    when (action) {
        "SEARCH" -> !secret && !locked
        "FORWARD", "EXPORT", "AI", "SCREENSHOT", "NOTIFICATION_PREVIEW" -> !secret
        else -> error("unknown action $action")
    }

internal fun secretReadOracle182(state: String, hasTimer: Boolean): String =
    if (state == "ACTIVE" && hasTimer) "ARMED" else state

internal fun secretExpiryOracle182(state: String): String =
    if (state == "ARMED") "EXPIRING" else state

internal fun secretDestroyOracle182(@Suppress("UNUSED_PARAMETER") state: String): String = "DESTROYED"

internal fun secretApplyOracle182(start: String, steps: List<String>): String {
    var state = start
    for (step in steps) {
        state = when (step) {
            "R0" -> secretReadOracle182(state, false)
            "R1" -> secretReadOracle182(state, true)
            "E" -> secretExpiryOracle182(state)
            "D" -> secretDestroyOracle182(state)
            else -> error("unknown step $step")
        }
    }
    return state
}

internal fun forwardOracle182(
    secret: Boolean,
    terminal: Boolean,
    forbids: Boolean,
    locked: Boolean,
): String = when {
    locked -> "PIN_LOCKED_BLOCKED"
    secret -> "SECRET_CHAT_BLOCKED"
    terminal -> "TERMINAL_BLOCKED"
    forbids -> "PRIVACY_BLOCKED"
    else -> "ALLOWED"
}

internal fun escJsonPriv182(s: String): String {
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
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

// ---------- production ----------

internal fun allowsProduction182(secret: Boolean, locked: Boolean, action: String): Boolean =
    ConversationPrivacyPolicy.allows(
        ConversationPrivacyCapabilities(isSecretChat = secret, isLocked = locked),
        PrivacyAction.valueOf(action),
    )

internal fun secretApplyProduction182(start: String, steps: List<String>): String {
    var state = SecretChatState.valueOf(start)
    for (step in steps) {
        state = when (step) {
            "R0" -> SecretChatStateMachine.onRead(state, false)
            "R1" -> SecretChatStateMachine.onRead(state, true)
            "E" -> SecretChatStateMachine.onExpiry(state)
            "D" -> SecretChatStateMachine.onDestroy(state)
            else -> error("unknown step $step")
        }
    }
    return state.name
}

internal fun forwardProduction182(
    secret: Boolean,
    terminal: Boolean,
    forbids: Boolean,
    locked: Boolean,
): String = ForwardPolicy.evaluate(secret, terminal, forbids, locked).name

// ---------- generators ----------

internal data class PrivCase182(val secret: Boolean, val locked: Boolean, val action: String)

internal data class SecretSeqCase182(val start: String, val steps: List<String>)

internal data class ForwardCase182(
    val secret: Boolean,
    val terminal: Boolean,
    val forbids: Boolean,
    val locked: Boolean,
)

internal val PRIV_ACTIONS182: List<String> = listOf(
    "SEARCH", "FORWARD", "EXPORT", "AI", "SCREENSHOT", "NOTIFICATION_PREVIEW",
)

internal val SECRET_STATES182: List<String> = listOf("ACTIVE", "ARMED", "EXPIRING", "DESTROYED")

internal val SECRET_STEPS182: List<String> = listOf("R0", "R1", "E", "D")

internal fun randomPrivCase182(rnd: Random): PrivCase182 =
    PrivCase182(rnd.nextBoolean(), rnd.nextBoolean(), PRIV_ACTIONS182[rnd.nextInt(PRIV_ACTIONS182.size)])

internal fun randomSecretSeqCase182(rnd: Random): SecretSeqCase182 {
    val start = SECRET_STATES182[rnd.nextInt(SECRET_STATES182.size)]
    val n = rnd.nextInt(0, 9)
    val steps = ArrayList<String>()
    repeat(n) { steps.add(SECRET_STEPS182[rnd.nextInt(SECRET_STEPS182.size)]) }
    return SecretSeqCase182(start, steps)
}

internal fun randomForwardCase182(rnd: Random): ForwardCase182 =
    ForwardCase182(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean())

internal fun privCaseWireJson182(c: PrivCase182): String =
    "{\"secret\":${c.secret},\"locked\":${c.locked},\"action\":${escJsonPriv182(c.action)}}"

internal fun secretSeqCaseWireJson182(c: SecretSeqCase182): String {
    val steps = c.steps.joinToString(",") { escJsonPriv182(it) }
    return "{\"start\":${escJsonPriv182(c.start)},\"steps\":[$steps]}"
}

internal fun forwardCaseWireJson182(c: ForwardCase182): String =
    "{\"secret\":${c.secret},\"terminal\":${c.terminal},\"forbids\":${c.forbids},\"locked\":${c.locked}}"

internal val PRIV_PINNED182: List<PrivCase182> = PRIV_ACTIONS182.flatMap { action ->
    listOf(false, true).flatMap { secret ->
        listOf(false, true).map { locked -> PrivCase182(secret, locked, action) }
    }
}

internal val FORWARD_PINNED182: List<ForwardCase182> =
    listOf(false, true).flatMap { secret ->
        listOf(false, true).flatMap { terminal ->
            listOf(false, true).flatMap { forbids ->
                listOf(false, true).map { locked -> ForwardCase182(secret, terminal, forbids, locked) }
            }
        }
    }

internal val SECRET_PINNED182: List<SecretSeqCase182> = listOf(
    SecretSeqCase182("ACTIVE", listOf()),
    SecretSeqCase182("ACTIVE", listOf("R0")),
    SecretSeqCase182("ACTIVE", listOf("R1")),
    SecretSeqCase182("ACTIVE", listOf("R1", "E")),
    SecretSeqCase182("ACTIVE", listOf("R1", "E", "D")),
    SecretSeqCase182("ACTIVE", listOf("D")),
    SecretSeqCase182("ACTIVE", listOf("E")),
    SecretSeqCase182("ARMED", listOf("R1")),
    SecretSeqCase182("ARMED", listOf("R0")),
    SecretSeqCase182("ARMED", listOf("E")),
    SecretSeqCase182("ARMED", listOf("E", "R1")),
    SecretSeqCase182("EXPIRING", listOf("R1", "E")),
    SecretSeqCase182("EXPIRING", listOf("E")),
    SecretSeqCase182("DESTROYED", listOf("R1")),
    SecretSeqCase182("DESTROYED", listOf("E")),
    SecretSeqCase182("DESTROYED", listOf("D")),
)

class ConversationPrivacyFuzzTest {

    private fun checkPriv182(c: PrivCase182) {
        assertEquals(
            allowsOracle182(c.secret, c.locked, c.action),
            allowsProduction182(c.secret, c.locked, c.action),
            "allows($c)",
        )
    }

    private fun checkSecretSeq182(c: SecretSeqCase182) {
        assertEquals(
            secretApplyOracle182(c.start, c.steps),
            secretApplyProduction182(c.start, c.steps),
            "secretSeq($c)",
        )
    }

    private fun checkForward182(c: ForwardCase182) {
        assertEquals(
            forwardOracle182(c.secret, c.terminal, c.forbids, c.locked),
            forwardProduction182(c.secret, c.terminal, c.forbids, c.locked),
            "forward($c)",
        )
    }

    @Test
    fun pinnedPrivacyGates() {
        for (c in PRIV_PINNED182) checkPriv182(c)
        for (c in SECRET_PINNED182) checkSecretSeq182(c)
        for (c in FORWARD_PINNED182) checkForward182(c)
    }

    @Test
    fun randomPrivacyGatesMatchProduction() {
        val rnd = Random(182002)
        repeat(1000) { checkPriv182(randomPrivCase182(rnd)) }
        repeat(1000) { checkSecretSeq182(randomSecretSeqCase182(rnd)) }
        repeat(1000) { checkForward182(randomForwardCase182(rnd)) }
    }

    @Test
    fun sweepPrivacyBoundaries() {
        // Every step sequence up to length 3 from every start state.
        for (start in SECRET_STATES182) {
            checkSecretSeq182(SecretSeqCase182(start, listOf()))
            for (a in SECRET_STEPS182) {
                checkSecretSeq182(SecretSeqCase182(start, listOf(a)))
                for (b in SECRET_STEPS182) {
                    checkSecretSeq182(SecretSeqCase182(start, listOf(a, b)))
                    for (d in SECRET_STEPS182) {
                        checkSecretSeq182(SecretSeqCase182(start, listOf(a, b, d)))
                    }
                }
            }
        }
        // Read-then-expiry-then-destroy chains from every state.
        for (start in SECRET_STATES182) {
            checkSecretSeq182(SecretSeqCase182(start, listOf("R1", "E", "D", "R1", "E")))
        }
    }
}
