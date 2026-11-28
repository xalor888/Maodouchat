package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.domain.messaging.MessageEventMissingTargetPolicy.MissingTargetDecision
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// decide reimplemented as a table: terminal actions (DELETE, REVOKE)
// tombstone first, so DELETE in both sets still tombstones; the
// receipt actions are no-ops; anything else is an error.
internal val EVENT_ACTIONS165 = listOf(
    "EDIT", "REVOKE", "DELETE", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT"
)

internal fun decideOracle165(action: String): MissingTargetDecision = when (action) {
    "REVOKE" -> MissingTargetDecision.Tombstone(MessageMutationKind.REVOKE)
    "DELETE" -> MissingTargetDecision.Tombstone(MessageMutationKind.DELETE)
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT" -> MissingTargetDecision.NoOp
    else -> MissingTargetDecision.Error
}

internal fun decisionName165(d: MissingTargetDecision): String = when (d) {
    is MissingTargetDecision.Tombstone -> "TOMBSTONE_" + d.kind.name
    MissingTargetDecision.NoOp -> "NOOP"
    MissingTargetDecision.Error -> "ERROR"
}

// ---------- generators ----------

internal val ACTION_POOL165: List<String> = listOf(
    "", " ", "EDIT", "edit", "Edit", "REVOKE", "revoke", "Revoke",
    "DELETE", "delete", "Delete", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT",
    "DELIVERY_RECEIP", "DELIVERY_RECEIPT ", " DELIVERY_RECEIPT",
    "READ_RECEIP", "PLAY_RECEIP", "REVOK", "REVOKEE", "DELET", "DELETEE",
    "EDITX", "XEDIT", "REACTION", "RECEIPT", "REVOKE\t", "DELETE\n",
    "delete ", " revoke", "UNKNOWN", "UNKNOWN_ACTION", "_", "-",
    "DELETE_DELETE", "REVOKE_REVOKE", "DELIVERY_RECEIPT\t",
)

internal const val ACTION_ALPHABET165 = "EDITREVOKDLA_ "

internal fun randomActionString165(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 10)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(ACTION_ALPHABET165[rnd.nextInt(ACTION_ALPHABET165.length)]) }
    return sb.toString()
}

internal fun randomActionCase165(rnd: Random): String {
    return when (rnd.nextInt(10)) {
        0 -> ACTION_POOL165[rnd.nextInt(ACTION_POOL165.size)]
        1 -> {
            val name = EVENT_ACTIONS165[rnd.nextInt(EVENT_ACTIONS165.size)]
            val sb = StringBuilder()
            for (ch in name) sb.append(if (rnd.nextBoolean()) ch.lowercaseChar() else ch)
            sb.toString()
        }
        else -> randomActionString165(rnd)
    }
}

internal fun actionWireJson165(action: String): String {
    val sb = StringBuilder()
    sb.append("{\"action\":\"")
    for (c in action) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    sb.append("\\u")
                    val v = c.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    sb.append("\"}")
    return sb.toString()
}

class MessageEventMissingTargetPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            "EDIT" to MissingTargetDecision.Error,
            "REVOKE" to MissingTargetDecision.Tombstone(MessageMutationKind.REVOKE),
            "DELETE" to MissingTargetDecision.Tombstone(MessageMutationKind.DELETE),
            "REACTION_SET" to MissingTargetDecision.Error,
            "REACTION_SNAPSHOT" to MissingTargetDecision.Error,
            "DELIVERY_RECEIPT" to MissingTargetDecision.NoOp,
            "READ_RECEIPT" to MissingTargetDecision.NoOp,
            "PLAY_RECEIPT" to MissingTargetDecision.NoOp,
            "" to MissingTargetDecision.Error,
            " " to MissingTargetDecision.Error,
            "edit" to MissingTargetDecision.Error,
            "revoke" to MissingTargetDecision.Error,
            "delete" to MissingTargetDecision.Error,
            "DELETE " to MissingTargetDecision.Error,
            " DELETE" to MissingTargetDecision.Error,
            "REVOKE\t" to MissingTargetDecision.Error,
            "DELETE\n" to MissingTargetDecision.Error,
            "REVOK" to MissingTargetDecision.Error,
            "REVOKEE" to MissingTargetDecision.Error,
            "DELET" to MissingTargetDecision.Error,
            "DELETEE" to MissingTargetDecision.Error,
            "DELIVERY_RECEIP" to MissingTargetDecision.Error,
            "READ_RECEIP" to MissingTargetDecision.Error,
            "PLAY_RECEIP" to MissingTargetDecision.Error,
            "UNKNOWN" to MissingTargetDecision.Error,
            "REACTION" to MissingTargetDecision.Error,
            "RECEIPT" to MissingTargetDecision.Error,
            "_" to MissingTargetDecision.Error,
        )
        for ((action, expected) in cases) {
            assertEquals(expected, MessageEventMissingTargetPolicy.decide(action), "decide($action)")
            assertEquals(expected, decideOracle165(action), "oracle($action)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(165001)
        repeat(4000) {
            val action = randomActionCase165(rnd)
            assertEquals(
                decideOracle165(action),
                MessageEventMissingTargetPolicy.decide(action),
                "decide($action)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        fun sweep(prefix: String, depth: Int) {
            if (depth == 0) {
                assertEquals(
                    decideOracle165(prefix),
                    MessageEventMissingTargetPolicy.decide(prefix),
                    "decide($prefix)"
                )
                return
            }
            sweep(prefix, depth - 1)
            for (ch in ACTION_ALPHABET165) sweep(prefix + ch, depth - 1)
        }
        sweep("", 4)
        for (raw in ACTION_POOL165) {
            assertEquals(decideOracle165(raw), MessageEventMissingTargetPolicy.decide(raw), "decide($raw)")
        }
        for (name in EVENT_ACTIONS165) {
            for (i in name.indices) {
                for (ch in ACTION_ALPHABET165) {
                    val mutated = name.substring(0, i) + ch + name.substring(i + 1)
                    assertEquals(
                        decideOracle165(mutated),
                        MessageEventMissingTargetPolicy.decide(mutated),
                        "decide($mutated)"
                    )
                }
                val deleted = name.substring(0, i) + name.substring(i + 1)
                assertEquals(
                    decideOracle165(deleted),
                    MessageEventMissingTargetPolicy.decide(deleted),
                    "decide($deleted)"
                )
            }
            for (i in 0..name.length) {
                for (ch in ACTION_ALPHABET165) {
                    val inserted = name.substring(0, i) + ch + name.substring(i)
                    assertEquals(
                        decideOracle165(inserted),
                        MessageEventMissingTargetPolicy.decide(inserted),
                        "decide($inserted)"
                    )
                }
            }
        }
    }
}
