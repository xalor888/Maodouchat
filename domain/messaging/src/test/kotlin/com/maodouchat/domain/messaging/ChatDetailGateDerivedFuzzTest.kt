package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// resolveChatDetailGateDerived restated by hand: pending while the lock
// state is unknown, blocking only once locked and not yet unlocked,
// disappearing only for a 1:1 chat with seconds left, secret only
// once the secret flag is set.
internal fun resolveGateOracle174(state: ChatDetailUiState): ChatDetailGateDerived =
    ChatDetailGateDerived(
        chatLockPending = state.isChatLocked == null,
        chatLockBlocking = state.isChatLocked == true && !state.isChatUnlocked,
        disappearingActive = !state.chatIsGroup && state.disappearingMessageSeconds > 0,
        secretActive = state.isSecretChat == true,
    )

// Production resolveChatDetailGateDerived returns the four derived flags.
internal fun resolveGateProduction174(state: ChatDetailUiState): ChatDetailGateDerived =
    resolveChatDetailGateDerived(state)

// ---------- generators ----------

internal data class GateCase174(
    val locked: Boolean?,
    val unlocked: Boolean,
    val isGroup: Boolean,
    val seconds: Int,
    val secret: Boolean?,
)

internal val GATE_LOCKED_POOL174: List<Boolean?> = listOf(null, true, false)
internal val GATE_SECRET_POOL174: List<Boolean?> = listOf(null, true, false)

internal val GATE_SECONDS_POOL174: List<Int> = listOf(
    Int.MIN_VALUE, -100, -1, 0, 1, 2, 60, 3600, Int.MAX_VALUE
)

internal fun buildGateState174(c: GateCase174): ChatDetailUiState = ChatDetailUiState(
    chatIsGroup = c.isGroup,
    disappearingMessageSeconds = c.seconds,
    isChatLocked = c.locked,
    isChatUnlocked = c.unlocked,
    isSecretChat = c.secret,
)

internal fun randomGateCase174(rnd: Random): GateCase174 = GateCase174(
    locked = GATE_LOCKED_POOL174[rnd.nextInt(GATE_LOCKED_POOL174.size)],
    unlocked = rnd.nextBoolean(),
    isGroup = rnd.nextBoolean(),
    seconds = GATE_SECONDS_POOL174[rnd.nextInt(GATE_SECONDS_POOL174.size)],
    secret = GATE_SECRET_POOL174[rnd.nextInt(GATE_SECRET_POOL174.size)],
)

internal fun gateWireJson174(c: GateCase174): String =
    "{\"locked\":${c.locked?.toString() ?: "null"}," +
        "\"unlocked\":${c.unlocked}," +
        "\"isGroup\":${c.isGroup}," +
        "\"seconds\":${c.seconds}," +
        "\"secret\":${c.secret?.toString() ?: "null"}}"

internal fun gateProjectionJson174(g: ChatDetailGateDerived): String =
    "{\"pending\":${g.chatLockPending}," +
        "\"blocking\":${g.chatLockBlocking}," +
        "\"disappearing\":${g.disappearingActive}," +
        "\"secret\":${g.secretActive}}"

class ChatDetailGateDerivedFuzzTest {

    private fun checkGate174(c: GateCase174) {
        val state = buildGateState174(c)
        assertEquals(
            gateProjectionJson174(resolveGateOracle174(state)),
            gateProjectionJson174(resolveGateProduction174(state)),
            "gate($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every lock state x every secret state, group and 1:1, at the
        // seconds boundary (0 stays off, 1 turns disappearing on).
        for (locked in GATE_LOCKED_POOL174) {
            for (secret in GATE_SECRET_POOL174) {
                for (isGroup in listOf(false, true)) {
                    for (seconds in listOf(-1, 0, 1)) {
                        for (unlocked in listOf(false, true)) {
                            checkGate174(GateCase174(locked, unlocked, isGroup, seconds, secret))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(174001)
        repeat(4000) { checkGate174(randomGateCase174(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Full cross product: lock x unlock x group x seconds x secret.
        for (locked in GATE_LOCKED_POOL174) {
            for (unlocked in listOf(false, true)) {
                for (isGroup in listOf(false, true)) {
                    for (seconds in GATE_SECONDS_POOL174) {
                        for (secret in GATE_SECRET_POOL174) {
                            checkGate174(GateCase174(locked, unlocked, isGroup, seconds, secret))
                        }
                    }
                }
            }
        }
    }
}
