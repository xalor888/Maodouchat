package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CallOfferSelectorFuzzTest {

    private val random = Random(2026_11_22L)

    private val terminalVariants = listOf(
        "hang-up", "HANG-UP", "Hang-Up", "hAnG-uP",
        "busy", "BUSY", "Busy", "bUsY",
        "reject", "REJECT", "Reject", "rEjEcT",
    )

    private val nearMissTypes = listOf(
        "hangup", "hang-up ", " busy", "hang_up", "rejected",
        "busy-", "-busy", "offer", "answer", "cancel", "ice-candidate",
        "hang", "bus", "rej", "",
    )

    private val typeChars = "abghunpsyjre- ".toList()

    private fun randomType(): String =
        when (random.nextInt(4)) {
            0 -> terminalVariants[random.nextInt(terminalVariants.size)]
            1 -> nearMissTypes[random.nextInt(nearMissTypes.size)]
            else -> buildString {
                repeat(random.nextInt(11)) { append(typeChars[random.nextInt(typeChars.size)]) }
            }
        }

    private fun randomCallId(): String =
        when (random.nextInt(4)) {
            0 -> ""
            1 -> "   "
            2 -> listOf("c1", "c2", "c3", "a", "b")[random.nextInt(5)]
            else -> buildString {
                repeat(1 + random.nextInt(6)) { append("abcxyz019".random(random)) }
            }
        }

    private fun msg(
        id: String,
        type: String = "offer",
        callId: String = "c1",
        groupId: String = "",
        groupInvite: Boolean = false,
    ) = SignalMessage(
        id = id, fromUserId = "u1", type = type, payload = "",
        timestamp = 1000L, callId = callId, groupId = groupId, groupInvite = groupInvite,
    )

    private fun randomMessage(index: Int): SignalMessage =
        msg(
            id = "m$index",
            type = randomType(),
            callId = randomCallId(),
            groupId = if (random.nextInt(3) == 0) "" else "g${random.nextInt(3)}",
            groupInvite = random.nextBoolean(),
        )

    // 终端判定逐字复述：lowercase 后恰为三词之一；带空白的变体不算。
    private fun oracleIsTerminal(type: String): Boolean {
        val t = type.lowercase()
        return t == "hang-up" || t == "busy" || t == "reject"
    }

    private fun oracleTerminated(messages: List<SignalMessage>): Set<String> =
        messages.filter { oracleIsTerminal(it.type) }
            .map { it.callId }
            .filter { it.isNotBlank() }
            .toSet()

    private fun oracleIsDirectRing(groupId: String, groupInvite: Boolean): Boolean =
        groupId.isBlank() || groupInvite

    private fun oracleSelectPrimary(
        offers: List<SignalMessage>,
        preferCallId: String,
    ): Pair<SignalMessage, List<SignalMessage>> {
        val index =
            if (preferCallId.isNotBlank()) {
                val found = offers.indexOfFirst { it.callId == preferCallId }
                if (found >= 0) found else 0
            } else {
                0
            }
        val primary = offers[index]
        return primary to offers.filterIndexed { i, _ -> i != index }
    }

    @Test
    fun `isTerminalType matches oracle over random types`() {
        repeat(300) {
            val type = randomType()
            assertEquals(oracleIsTerminal(type), CallOfferSelector.isTerminalType(type), "type=$type")
        }
    }

    @Test
    fun `terminal variants are recognised in every case`() {
        for (variant in terminalVariants) {
            assertTrue(CallOfferSelector.isTerminalType(variant), "type=$variant")
        }
        // 近似词与带空白变体一律不算终端信令。
        for (near in nearMissTypes) {
            if (near.isEmpty()) continue
            assertFalse(CallOfferSelector.isTerminalType(near), "type=$near")
        }
    }

    @Test
    fun `terminatedCallIds matches oracle over random message lists`() {
        repeat(200) {
            val size = random.nextInt(7)
            val messages = List(size) { randomMessage(it) }
            assertEquals(
                oracleTerminated(messages),
                CallOfferSelector.terminatedCallIds(messages),
                "types=${messages.map { it.type }} ids=${messages.map { it.callId }}",
            )
        }
    }

    @Test
    fun `terminatedCallIds never contains blank ids`() {
        repeat(200) {
            val size = random.nextInt(7)
            val messages = List(size) { randomMessage(it) }
            val ids = CallOfferSelector.terminatedCallIds(messages)
            assertTrue(ids.none { it.isBlank() }, "ids=$ids")
        }
    }

    @Test
    fun `isDirectRingOffer matches oracle over random group ids`() {
        repeat(200) {
            val groupId = when (random.nextInt(3)) {
                0 -> ""
                1 -> "   "
                else -> "g${random.nextInt(3)}"
            }
            val invite = random.nextBoolean()
            assertEquals(
                oracleIsDirectRing(groupId, invite),
                CallOfferSelector.isDirectRingOffer(groupId, invite),
                "groupId=$groupId invite=$invite",
            )
        }
    }

    @Test
    fun `selectPrimary matches oracle over random offers and prefer ids`() {
        repeat(200) {
            val size = 1 + random.nextInt(5)
            val offers = List(size) { randomMessage(it) }
            val prefer = when (random.nextInt(3)) {
                0 -> ""
                1 -> "   "
                else -> randomCallId()
            }
            val (expectedPrimary, expectedRest) = oracleSelectPrimary(offers, prefer)
            val (primary, rest) = CallOfferSelector.selectPrimary(offers, prefer)
            assertEquals(expectedPrimary, primary, "offers=${offers.map { it.callId }} prefer=$prefer")
            assertEquals(
                expectedRest.map { it.id },
                rest.map { it.id },
                "offers=${offers.map { it.callId }} prefer=$prefer",
            )
        }
    }

    @Test
    fun `selectPrimary rest keeps order and drops only the primary instance`() {
        repeat(200) {
            val size = 1 + random.nextInt(5)
            val offers = List(size) { randomMessage(it) }
            val prefer = randomCallId()
            val (primary, rest) = CallOfferSelector.selectPrimary(offers, prefer)
            assertEquals(size - 1, rest.size, "offers=${offers.map { it.callId }} prefer=$prefer")
            assertTrue(rest.none { it === primary }, "primary=${primary.id}")
            assertEquals(
                offers.filter { it !== primary }.map { it.id },
                rest.map { it.id },
                "offers=${offers.map { it.callId }} prefer=$prefer",
            )
        }
    }

    @Test
    fun `empty offers throw and duplicate call ids keep identity`() {
        try {
            CallOfferSelector.selectPrimary(emptyList(), "c1")
            fail("空列表应当抛 NoSuchElementException")
        } catch (e: NoSuchElementException) {
        }
        // 同 callId 两个不同实例：prefer 命中第一条，rest 只剔掉 primary 本体。
        val first = msg(id = "m1", callId = "c1")
        val dup = msg(id = "m1b", callId = "c1")
        val (primary, rest) = CallOfferSelector.selectPrimary(listOf(first, dup), "c1")
        assertEquals("m1", primary.id)
        assertEquals(listOf("m1b"), rest.map { it.id })
    }
}
