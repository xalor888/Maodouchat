package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallReliabilityPolicyFuzzTest {

    private val random = Random(2026112241L)

    private val idPool = listOf("", " ", "  ", "alice", "bob", "Alice", "ALICE", "a", "ab", "user-1", "user-10")

    private fun randomId(): String = idPool.random(random)

    private fun randomCallType(): CallType =
        listOf(CallType.AUDIO, CallType.VIDEO, CallType.GROUP).random(random)

    // 与生产侧逐行同构的 oracle：VIDEO 要麦克风 + 摄像头，其余只要麦克风。
    private fun oraclePermissions(callType: CallType): Set<CallMediaPermission> =
        if (callType == CallType.VIDEO) {
            setOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA)
        } else {
            setOf(CallMediaPermission.MICROPHONE)
        }

    // 空白 fromUserId 一律拒绝；群聊放行；否则要求 expected 为空或与 from 一致。
    private fun oracleAcceptSignal(isGroupCall: Boolean, expectedContactId: String, fromUserId: String): Boolean {
        if (fromUserId.isBlank()) return false
        return isGroupCall || expectedContactId.isBlank() || expectedContactId == fromUserId
    }

    // 未绑定会话（active 为空）可接受任意初始信令；已绑定则要求精确一致。
    private fun oracleAcceptCallId(activeCallId: String, incomingCallId: String): Boolean =
        activeCallId.isBlank() || activeCallId == incomingCallId

    private fun oracleGridColumns(participantCount: Int): Int = if (participantCount <= 4) 2 else 3

    // 任一 groupId 为空即放行；否则要求同组且成员集合相等（顺序与重复无关）。
    private fun oracleAcceptMetadata(
        activeGroupId: String,
        incomingGroupId: String,
        activeMembers: Collection<String>,
        incomingMembers: Collection<String>,
    ): Boolean {
        if (activeGroupId.isBlank() || incomingGroupId.isBlank()) return true
        return activeGroupId == incomingGroupId && activeMembers.toSet() == incomingMembers.toSet()
    }

    @Test
    fun `requiredPermissions matches the oracle for random call types`() {
        repeat(200) {
            val type = randomCallType()
            assertEquals(oraclePermissions(type), CallReliabilityPolicy.requiredPermissions(type))
        }
    }

    @Test
    fun `requiredPermissions pins the video audio split`() {
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA),
            CallReliabilityPolicy.requiredPermissions(CallType.VIDEO),
        )
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE),
            CallReliabilityPolicy.requiredPermissions(CallType.AUDIO),
        )
        assertEquals(
            setOf(CallMediaPermission.MICROPHONE),
            CallReliabilityPolicy.requiredPermissions(CallType.GROUP),
        )
    }

    @Test
    fun `shouldAcceptSignal matches the oracle for random ids`() {
        repeat(300) {
            val isGroup = random.nextBoolean()
            val expected = randomId()
            val from = randomId()
            assertEquals(
                oracleAcceptSignal(isGroup, expected, from),
                CallReliabilityPolicy.shouldAcceptSignal(isGroup, expected, from),
                "isGroup=$isGroup expected=$expected from=$from",
            )
        }
    }

    @Test
    fun `shouldAcceptSignal pins blank sender and blank expected`() {
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(false, "alice", ""))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(true, "alice", "   "))
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(false, "", "alice"))
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(false, "alice", "alice"))
        assertFalse(CallReliabilityPolicy.shouldAcceptSignal(false, "alice", "bob"))
        assertTrue(CallReliabilityPolicy.shouldAcceptSignal(true, "alice", "bob"))
    }

    @Test
    fun `shouldAcceptCallId matches the oracle for random ids`() {
        repeat(300) {
            val active = randomId()
            val incoming = randomId()
            assertEquals(
                oracleAcceptCallId(active, incoming),
                CallReliabilityPolicy.shouldAcceptCallId(active, incoming),
                "active=$active incoming=$incoming",
            )
        }
    }

    @Test
    fun `shouldAcceptCallId pins blank active accepts anything`() {
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("", ""))
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("   ", "call-1"))
        assertTrue(CallReliabilityPolicy.shouldAcceptCallId("call-1", "call-1"))
        assertFalse(CallReliabilityPolicy.shouldAcceptCallId("call-1", "call-2"))
        assertFalse(CallReliabilityPolicy.shouldAcceptCallId("call-1", ""))
    }

    @Test
    fun `normalizeSignalingType matches trim plus lowercase for random strings`() {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '\t', '-', '_', '.')
        repeat(300) {
            val raw = (0 until random.nextInt(0, 12)).map { alphabet.random(random) }.joinToString("")
            assertEquals(
                raw.trim().lowercase(),
                CallReliabilityPolicy.normalizeSignalingType(raw),
                "raw=$raw",
            )
        }
    }

    @Test
    fun `normalizeSignalingType pins case and padding`() {
        assertEquals("offer", CallReliabilityPolicy.normalizeSignalingType("  OFFER "))
        assertEquals("hang-up", CallReliabilityPolicy.normalizeSignalingType("Hang-Up"))
        assertEquals("", CallReliabilityPolicy.normalizeSignalingType("   "))
        assertEquals("", CallReliabilityPolicy.normalizeSignalingType(""))
        assertEquals("äöü", CallReliabilityPolicy.normalizeSignalingType("ÄÖÜ"))
        assertEquals("ος", CallReliabilityPolicy.normalizeSignalingType("ΟΣ"))
        assertEquals("i̇", CallReliabilityPolicy.normalizeSignalingType("İ"))
    }

    @Test
    fun `gridColumns matches the oracle for random counts`() {
        val counts = listOf(-10, -1, 0, 1, 2, 3, 4, 5, 6, 100, Int.MAX_VALUE, Int.MIN_VALUE)
        repeat(200) {
            val count = counts.random(random)
            assertEquals(oracleGridColumns(count), GroupCallPolicy.gridColumns(count), "count=$count")
        }
    }

    @Test
    fun `gridColumns pins the four participant boundary`() {
        assertEquals(2, GroupCallPolicy.gridColumns(4))
        assertEquals(3, GroupCallPolicy.gridColumns(5))
        assertEquals(2, GroupCallPolicy.gridColumns(0))
    }

    @Test
    fun `shouldAcceptMetadata matches the oracle for random groups`() {
        val groups = listOf("", " ", "g1", "g2")
        val members = listOf("", "a", "b", "c")
        repeat(300) {
            val activeGroup = groups.random(random)
            val incomingGroup = groups.random(random)
            val activeMembers = List(random.nextInt(0, 5)) { members.random(random) }
            val incomingMembers = List(random.nextInt(0, 5)) { members.random(random) }
            assertEquals(
                oracleAcceptMetadata(activeGroup, incomingGroup, activeMembers, incomingMembers),
                GroupCallPolicy.shouldAcceptMetadata(activeGroup, incomingGroup, activeMembers, incomingMembers),
                "active=$activeGroup incoming=$incomingGroup activeMembers=$activeMembers incomingMembers=$incomingMembers",
            )
        }
    }

    @Test
    fun `shouldAcceptMetadata pins order and duplicates as equal`() {
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("g1", "g1", listOf("a", "b"), listOf("b", "a")))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("g1", "g1", listOf("a", "a", "b"), listOf("a", "b")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("g1", "g1", listOf("a"), listOf("a", "b")))
        assertFalse(GroupCallPolicy.shouldAcceptMetadata("g1", "g2", listOf("a"), listOf("a")))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("", "g2", listOf("a"), listOf("b")))
        assertTrue(GroupCallPolicy.shouldAcceptMetadata("g1", "   ", listOf("a"), listOf("b")))
    }

    @Test
    fun `max mesh members is six`() {
        assertEquals(6, GroupCallPolicy.MAX_MESH_MEMBERS)
    }
}
