package com.maodouchat.call

import com.maodouchat.webrtc.WebRTCSignaling.SignalMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallOfferSelectorTest {

    private fun msg(
        id: String = "m",
        type: String = "offer",
        callId: String = "c1",
        from: String = "u1",
        groupId: String = "",
        groupInvite: Boolean = false,
    ) = SignalMessage(
        id = id, fromUserId = from, type = type, payload = "",
        timestamp = 1000L, callId = callId, groupId = groupId, groupInvite = groupInvite,
    )

    @Test
    fun terminalTypesAreCaseInsensitive() {
        assertTrue(CallOfferSelector.isTerminalType("hang-up"))
        assertTrue(CallOfferSelector.isTerminalType("BUSY"))
        assertTrue(CallOfferSelector.isTerminalType("Reject"))
        assertFalse(CallOfferSelector.isTerminalType("offer"))
        assertFalse(CallOfferSelector.isTerminalType("answer"))
        assertFalse(CallOfferSelector.isTerminalType(""))
    }

    @Test
    fun terminatedIdsSkipBlanks() {
        val ids = CallOfferSelector.terminatedCallIds(
            listOf(msg(type = "hang-up", callId = "a"), msg(type = "busy", callId = ""), msg(type = "offer", callId = "b"))
        )
        assertEquals(setOf("a"), ids)
    }

    @Test
    fun meshEdgeOffersExcluded() {
        assertTrue(CallOfferSelector.isDirectRingOffer("", false))
        assertTrue(CallOfferSelector.isDirectRingOffer("g1", true))
        assertFalse(CallOfferSelector.isDirectRingOffer("g1", false))
    }

    @Test
    fun preferCallIdWins() {
        val offers = listOf(msg(id = "m1", callId = "c1"), msg(id = "m2", callId = "c2"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "c2")
        assertEquals("m2", primary.id)
        assertEquals(listOf("m1"), rest.map { it.id })
    }

    @Test
    fun noPreferFallsBackToFirst() {
        val offers = listOf(msg(id = "m1", callId = "c1"), msg(id = "m2", callId = "c2"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "")
        assertEquals("m1", primary.id)
        assertEquals(listOf("m2"), rest.map { it.id })
    }

    @Test
    fun unmatchedPreferFallsBackToFirst() {
        val offers = listOf(msg(id = "m1", callId = "c1"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "ghost")
        assertEquals("m1", primary.id)
        assertTrue(rest.isEmpty())
    }
}
