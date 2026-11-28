package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// evaluate restated as a decision code: 0 allowed, 1 locked, 2 secret.
// Locked wins over secret; secret only rejects the two background
// capabilities (schedule index 3, quick reply index 4).
internal fun evaluateOracle168(capability: Int, isSecret: Boolean, isLocked: Boolean): Int = when {
    isLocked -> 1
    isSecret && capability in setOf(3, 4) -> 2
    else -> 0
}

// Production evaluate returns a decision; map it to the same code
// the oracle returns.
internal fun evaluateProduction168(
    policy: ConversationCommandPrivacyPolicy,
    capability: Int,
    isSecret: Boolean,
    isLocked: Boolean
): Int {
    val decision = policy.evaluate(
        ConversationCapability.entries[capability],
        ConversationPrivacyContext(isSecret = isSecret, isLocked = isLocked)
    )
    return when (decision) {
        ConversationPrivacyDecision.Allowed -> 0
        is ConversationPrivacyDecision.Rejected -> when (decision.reason) {
            ConversationPrivacyRejection.LOCKED_CONVERSATION -> 1
            ConversationPrivacyRejection.SECRET_CONVERSATION -> 2
        }
    }
}

// ---------- generators ----------

internal data class PrivCase168(
    val capability: Int,
    val isSecret: Boolean,
    val isLocked: Boolean
)

internal fun randomPrivCase168(rnd: Random): PrivCase168 = PrivCase168(
    capability = rnd.nextInt(5),
    isSecret = rnd.nextBoolean(),
    isLocked = rnd.nextBoolean()
)

internal fun privWireJson168(c: PrivCase168): String =
    "{\"cap\":${c.capability},\"secret\":${c.isSecret},\"locked\":${c.isLocked}}"

class ConversationCommandPrivacyPolicyFuzzTest {

    private val policy = ConversationCommandPrivacyPolicy()

    @Test
    fun pinnedBoundaries() {
        // All 5 capabilities x locked x secret: 20 cases, exhaustive.
        // Locked rejects everything; secret rejects only the two
        // background capabilities; anything else is allowed.
        for (cap in 0 until 5) {
            for (locked in listOf(false, true)) {
                for (secret in listOf(false, true)) {
                    val expected = when {
                        locked -> 1
                        secret && cap in setOf(3, 4) -> 2
                        else -> 0
                    }
                    assertEquals(
                        expected,
                        evaluateProduction168(policy, cap, secret, locked),
                        "evaluate(cap=$cap,secret=$secret,locked=$locked)"
                    )
                    assertEquals(
                        expected,
                        evaluateOracle168(cap, secret, locked),
                        "oracle(cap=$cap,secret=$secret,locked=$locked)"
                    )
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(168001)
        repeat(4000) {
            val c = randomPrivCase168(rnd)
            assertEquals(
                evaluateOracle168(c.capability, c.isSecret, c.isLocked),
                evaluateProduction168(policy, c.capability, c.isSecret, c.isLocked),
                "evaluate(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Locked wins over secret for every capability.
        for (cap in 0 until 5) {
            assertEquals(
                evaluateOracle168(cap, isSecret = true, isLocked = true),
                evaluateProduction168(policy, cap, isSecret = true, isLocked = true),
                "sweep-locked-wins(cap=$cap)"
            )
        }
        // Secret rejects only the background pair.
        for (cap in 0 until 5) {
            assertEquals(
                evaluateOracle168(cap, isSecret = true, isLocked = false),
                evaluateProduction168(policy, cap, isSecret = true, isLocked = false),
                "sweep-secret(cap=$cap)"
            )
        }
        // Open context allows every capability.
        for (cap in 0 until 5) {
            assertEquals(
                evaluateOracle168(cap, isSecret = false, isLocked = false),
                evaluateProduction168(policy, cap, isSecret = false, isLocked = false),
                "sweep-open(cap=$cap)"
            )
        }
    }
}
