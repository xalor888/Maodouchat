package com.maodouchat.call

import com.maodouchat.webrtc.SignalMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun terminalTypesRejectPaddedInput() {
        // lowercase 之后才比较：带空白的变体不被当作终端信令。
        assertFalse(CallOfferSelector.isTerminalType(" busy "))
        assertFalse(CallOfferSelector.isTerminalType("hang-up "))
        assertFalse(CallOfferSelector.isTerminalType("HangUp"))
    }

    @Test
    fun terminatedIdsDeduplicate() {
        val ids = CallOfferSelector.terminatedCallIds(
            listOf(
                msg(type = "hang-up", callId = "a"),
                msg(type = "HANG-UP", callId = "a"),
                msg(type = "busy", callId = "b"),
            )
        )
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun whitespaceGroupIdStillDirectRing() {
        assertTrue(CallOfferSelector.isDirectRingOffer("  ", false))
    }

    @Test
    fun preferMiddleKeepsRelativeOrder() {
        val offers = listOf(msg(id = "m1", callId = "c1"), msg(id = "m2", callId = "c2"), msg(id = "m3", callId = "c3"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "c2")
        assertEquals("m2", primary.id)
        assertEquals(listOf("m1", "m3"), rest.map { it.id })
    }

    @Test
    fun singleOfferPreferMatchYieldsEmptyRest() {
        val offers = listOf(msg(id = "m1", callId = "c1"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "c1")
        assertEquals("m1", primary.id)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun emptyOffersThrowNoSuchElement() {
        // 失败：空列表无首选可回退——调用方须保证非空，这里钉死抛错语义。
        assertFailsWith<NoSuchElementException> {
            CallOfferSelector.selectPrimary(emptyList(), "c1")
        }
    }

    @Test
    fun duplicateCallIdsKeepFirstMatchOnly() {
        // 重复：同 callId 出现两次——prefer 命中第一条实例，rest 用引用相等排除，只剔掉 primary 本体。
        val first = msg(id = "m1", callId = "c1")
        val dup = msg(id = "m1b", callId = "c1")
        val (primary, rest) = CallOfferSelector.selectPrimary(listOf(first, dup), "c1")
        assertEquals("m1", primary.id)
        assertEquals(listOf("m1b"), rest.map { it.id })
    }

    @Test
    fun selectionIgnoresFromUserId() {
        // 账号切换：选择只看 callId，fromUserId 不同不影响 prefer 命中。
        val offers = listOf(
            msg(id = "m1", callId = "c1", from = "u1"),
            msg(id = "m2", callId = "c2", from = "u9"),
        )
        val (primary, _) = CallOfferSelector.selectPrimary(offers, "c2")
        assertEquals("m2", primary.id)
    }

    @Test
    fun rejectTypeCountsAsTerminated() {
        // 取消：拒接即终止——reject 进 terminatedCallIds。
        val ids = CallOfferSelector.terminatedCallIds(
            listOf(msg(type = "reject", callId = "r1"), msg(type = "offer", callId = "o1"))
        )
        assertEquals(setOf("r1"), ids)
    }

    @Test
    fun blankPreferFallsBackToFirst() {
        // 空白 prefer 视为未指定——回退到首个。
        val offers = listOf(msg(id = "m1", callId = "c1"), msg(id = "m2", callId = "c2"))
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, "   ")
        assertEquals("m1", primary.id)
        assertEquals(listOf("m2"), rest.map { it.id })
    }
}
