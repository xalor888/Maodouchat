package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// evaluate restated as a triple: unread delta, notify flag, receipt flag.
// Visible incoming means new, non-blank owner, sender is not the owner,
// and the type is not a key-distribution message.
internal fun evaluateOracle169(
    isNew: Boolean,
    ownerUserId: String,
    senderUserId: String,
    conversationId: String,
    messageType: Int,
    envelopeKind: String,
    isConversationOccupied: Boolean,
    appInForeground: Boolean,
    activeChatId: String?,
    openChatDetailId: String?
): Triple<Int, Boolean, Boolean> {
    val type = MessageType.entries[messageType]
    val visible = isNew && ownerUserId.isNotBlank() &&
        senderUserId != ownerUserId && type != MessageType.SK_DIST
    return Triple(
        if (visible && !isConversationOccupied) 1 else 0,
        visible && !appInForeground &&
            activeChatId != conversationId && openChatDetailId != conversationId,
        visible && envelopeKind != "SERVICE"
    )
}

// Production evaluate returns a decision; map it to the same triple
// the oracle returns.
internal fun evaluateProduction169(
    isNew: Boolean,
    ownerUserId: String,
    senderUserId: String,
    conversationId: String,
    messageType: Int,
    envelopeKind: String,
    isConversationOccupied: Boolean,
    appInForeground: Boolean,
    activeChatId: String?,
    openChatDetailId: String?
): Triple<Int, Boolean, Boolean> {
    val d = MessagingV2ArrivalPolicy.evaluate(
        isNew, ownerUserId, senderUserId, conversationId,
        MessageType.entries[messageType], envelopeKind,
        isConversationOccupied, appInForeground, activeChatId, openChatDetailId
    )
    return Triple(d.unreadDelta, d.shouldAttemptNotification, d.shouldSendDeliveryReceipt)
}

// ---------- generators ----------

internal data class ArrivalCase169(
    val isNew: Boolean,
    val ownerUserId: String,
    val senderUserId: String,
    val conversationId: String,
    val messageType: Int,
    val envelopeKind: String,
    val isConversationOccupied: Boolean,
    val appInForeground: Boolean,
    val activeChatId: String?,
    val openChatDetailId: String?
)

internal val ARRIVAL_ID_POOL169: List<String> = listOf(
    "", "u1", "u2", "c1", "c2", " ", "  ", "\u00A0", "\u200B", "a b", "U1"
)

internal val ARRIVAL_KIND_POOL169: List<String> = listOf(
    "", "SERVICE", "service", "Service", "MESSAGE", " SERVICE", "SERVICE "
)

internal val ARRIVAL_NULLABLE_POOL169: List<String?> = listOf(
    null, "", "c1", "c2", "u1", " ", "\u00A0"
)

internal fun randomArrivalCase169(rnd: Random): ArrivalCase169 = ArrivalCase169(
    isNew = rnd.nextBoolean(),
    ownerUserId = ARRIVAL_ID_POOL169[rnd.nextInt(ARRIVAL_ID_POOL169.size)],
    senderUserId = ARRIVAL_ID_POOL169[rnd.nextInt(ARRIVAL_ID_POOL169.size)],
    conversationId = ARRIVAL_ID_POOL169[rnd.nextInt(ARRIVAL_ID_POOL169.size)],
    messageType = rnd.nextInt(13),
    envelopeKind = ARRIVAL_KIND_POOL169[rnd.nextInt(ARRIVAL_KIND_POOL169.size)],
    isConversationOccupied = rnd.nextBoolean(),
    appInForeground = rnd.nextBoolean(),
    activeChatId = ARRIVAL_NULLABLE_POOL169[rnd.nextInt(ARRIVAL_NULLABLE_POOL169.size)],
    openChatDetailId = ARRIVAL_NULLABLE_POOL169[rnd.nextInt(ARRIVAL_NULLABLE_POOL169.size)]
)

internal fun arrivalWireJson169(c: ArrivalCase169): String {
    val active = if (c.activeChatId == null) "null" else "\"${c.activeChatId}\""
    val open = if (c.openChatDetailId == null) "null" else "\"${c.openChatDetailId}\""
    return "{\"isNew\":${c.isNew},\"owner\":\"${c.ownerUserId}\",\"sender\":\"${c.senderUserId}\"," +
        "\"conv\":\"${c.conversationId}\",\"type\":${c.messageType}," +
        "\"kind\":\"${c.envelopeKind}\",\"occupied\":${c.isConversationOccupied}," +
        "\"fg\":${c.appInForeground},\"active\":$active,\"open\":$open}"
}

class MessagingV2ArrivalPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Visible incoming: new, non-blank owner, other sender, any
        // type but SK_DIST. Every type, both occupied states.
        for (type in 0 until 13) {
            for (occupied in listOf(false, true)) {
                val expected = if (type == 10) Triple(0, false, false)
                else Triple(if (occupied) 0 else 1, true, true)
                assertEquals(
                    expected,
                    evaluateProduction169(
                        true, "u1", "u2", "c1", type, "MESSAGE",
                        occupied, false, null, null
                    ),
                    "evaluate-visible(type=$type,occupied=$occupied)"
                )
                assertEquals(
                    expected,
                    evaluateOracle169(
                        true, "u1", "u2", "c1", type, "MESSAGE",
                        occupied, false, null, null
                    ),
                    "oracle-visible(type=$type,occupied=$occupied)"
                )
            }
        }
        // Not new, blank owner, own message: nothing is visible.
        val dark = Triple(0, false, false)
        for ((isNew, owner, sender) in listOf(
            Triple(false, "u1", "u2"),
            Triple(true, "", "u2"),
            Triple(true, "   ", "u2"),
            Triple(true, "\u00A0", "u2"),
            Triple(true, "u1", "u1")
        )) {
            assertEquals(
                dark,
                evaluateProduction169(
                    isNew, owner, sender, "c1", 0, "MESSAGE", false, false, null, null
                ),
                "evaluate-dark(isNew=$isNew,owner=$owner,sender=$sender)"
            )
            assertEquals(
                dark,
                evaluateOracle169(
                    isNew, owner, sender, "c1", 0, "MESSAGE", false, false, null, null
                ),
                "oracle-dark(isNew=$isNew,owner=$owner,sender=$sender)"
            )
        }
        // Foreground suppresses the notification only; the receipt
        // still goes out and the unread count still grows.
        assertEquals(
            Triple(1, false, true),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "MESSAGE", false, true, null, null
            ),
            "evaluate-foreground"
        )
        // An open chat detail showing this conversation suppresses the
        // notification; a different open chat does not.
        assertEquals(
            Triple(1, false, true),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "MESSAGE", false, false, null, "c1"
            ),
            "evaluate-open-same"
        )
        assertEquals(
            Triple(1, true, true),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "MESSAGE", false, false, null, "c2"
            ),
            "evaluate-open-other"
        )
        // The active chat being this conversation suppresses the
        // notification as well.
        assertEquals(
            Triple(1, false, true),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "MESSAGE", false, false, "c1", null
            ),
            "evaluate-active-same"
        )
        // A SERVICE envelope never asks for a delivery receipt.
        assertEquals(
            Triple(1, true, false),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "SERVICE", false, false, null, null
            ),
            "evaluate-service-envelope"
        )
        // The envelope kind match is exact: lowercase "service" is a
        // normal envelope and still gets a receipt.
        assertEquals(
            Triple(1, true, true),
            evaluateProduction169(
                true, "u1", "u2", "c1", 0, "service", false, false, null, null
            ),
            "evaluate-service-lowercase"
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(169001)
        repeat(4000) {
            val c = randomArrivalCase169(rnd)
            assertEquals(
                evaluateOracle169(
                    c.isNew, c.ownerUserId, c.senderUserId, c.conversationId,
                    c.messageType, c.envelopeKind, c.isConversationOccupied,
                    c.appInForeground, c.activeChatId, c.openChatDetailId
                ),
                evaluateProduction169(
                    c.isNew, c.ownerUserId, c.senderUserId, c.conversationId,
                    c.messageType, c.envelopeKind, c.isConversationOccupied,
                    c.appInForeground, c.activeChatId, c.openChatDetailId
                ),
                "evaluate(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type with the canonical visible setup, then with the
        // foreground on, then with a SERVICE envelope.
        for (type in 0 until 13) {
            assertEquals(
                evaluateOracle169(true, "u1", "u2", "c1", type, "MESSAGE", false, false, null, null),
                evaluateProduction169(true, "u1", "u2", "c1", type, "MESSAGE", false, false, null, null),
                "sweep-visible(type=$type)"
            )
            assertEquals(
                evaluateOracle169(true, "u1", "u2", "c1", type, "MESSAGE", false, true, null, null),
                evaluateProduction169(true, "u1", "u2", "c1", type, "MESSAGE", false, true, null, null),
                "sweep-foreground(type=$type)"
            )
            assertEquals(
                evaluateOracle169(true, "u1", "u2", "c1", type, "SERVICE", false, false, null, null),
                evaluateProduction169(true, "u1", "u2", "c1", type, "SERVICE", false, false, null, null),
                "sweep-service(type=$type)"
            )
        }
        // Every whitespace unit as the whole owner id: blank owners
        // are never visible.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val owner = u.toChar().toString()
            assertEquals(
                evaluateOracle169(true, owner, "u2", "c1", 0, "MESSAGE", false, false, null, null),
                evaluateProduction169(true, owner, "u2", "c1", 0, "MESSAGE", false, false, null, null),
                "sweep-owner-ws(u=$u)"
            )
        }
        // A zero-width space is not blank, so it counts as an owner.
        assertEquals(
            Triple(1, true, true),
            evaluateProduction169(true, "\u200B", "u2", "c1", 0, "MESSAGE", false, false, null, null),
            "sweep-owner-zwsp"
        )
    }
}
