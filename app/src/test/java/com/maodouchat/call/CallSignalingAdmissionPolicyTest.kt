package com.maodouchat.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingAdmissionPolicyTest {

    @Test
    fun `direct call drops foreign sender`() {
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "b",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `mismatched call id busy-rejects offer and drops ice`() {
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "ice-candidate",
            ),
        )
    }

    @Test
    fun `stale epoch sequence is dropped`() {
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 1,
                incomingSequence = 1,
                lastAcceptedCursor = Cursor(1, 2),
            ),
        )
    }

    @Test
    fun `blank sender is always dropped`() {
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
        // 群通话也不豁免空发送者
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "  ",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `group call accepts any non-blank sender`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "b",
                activeCallId = "",
                incomingCallId = "call-9",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `group metadata mismatch busy-rejects offer and drops answer`() {
        // 群 id 不一致
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g2",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = "offer",
            ),
        )
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g2",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = "answer",
            ),
        )
        // 群 id 一致但成员集合不一致同样拒绝
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "c"),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `legacy zero cursor bypasses order check`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 0,
                incomingSequence = 0,
                lastAcceptedCursor = Cursor(5, 9),
            ),
        )
    }

    @Test
    fun `signal type normalization drives busy-reject decision`() {
        // " OFFER " 归一化后仍是 offer → 忙拒绝
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = " OFFER ",
            ),
        )
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = " ICE-CANDIDATE ",
            ),
        )
    }

    @Test
    fun `matching call accepts and unbound receiver accepts initial offer`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 1,
                incomingSequence = 3,
                lastAcceptedCursor = Cursor(1, 2),
            ),
        )
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-new",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `equal cursor re-delivery is accepted as benign repeat`() {
        // 同游标 compare 为 0 而非落后 → 放行，去重由下游幂等层承担。
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 1,
                incomingSequence = 2,
                lastAcceptedCursor = Cursor(1, 2),
            ),
        )
    }

    @Test
    fun `higher epoch is accepted even with older sequence`() {
        // epoch 先比：2 > 1 直接胜出，sequence 不再参与判定。
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 2,
                incomingSequence = 0,
                lastAcceptedCursor = Cursor(1, 9),
            ),
        )
    }

    @Test
    fun `first signal of a fresh session is accepted without cursor`() {
        // 账号切换后新会话尚无已接受游标（null），首信令直接放行。
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 3,
                incomingSequence = 1,
                lastAcceptedCursor = null,
            ),
        )
    }

    @Test
    fun `hangup signal for a foreign call id is dropped silently`() {
        // 取消类信令只给 offer 发忙拒绝，其余陌生 callId 信令静默丢弃。
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-9",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "hang-up",
            ),
        )
    }

    @Test
    fun `matching group metadata still busy-rejects a foreign call offer`() {
        // 群元数据一致先过审，接着 callId 不一致仍忙拒绝。
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `群元数据一致且游标递增时群通话被接受`() {
        // 群元数据全匹配（含成员顺序无关）+ epoch/sequence 前进 → 完整路径放行。
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "b",
                activeCallId = "",
                incomingCallId = "call-g",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("b", "a"),
                signalType = "offer",
                incomingEpoch = 1,
                incomingSequence = 2,
                lastAcceptedCursor = Cursor(1, 1),
            ),
        )
    }

    @Test
    fun `空 incomingCallId 不能绑定活跃通话`() {
        // 活跃通话要求精确 id：空 incoming 绑定不上，offer 照样忙拒绝。
        assertEquals(
            Decision.BUSY_REJECT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `自己活跃通话的 hang-up 信令被接受`() {
        // 取消类信令只给陌生 callId 丢弃：callId 对上自己就放行。
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "hang-up",
            ),
        )
    }

    @Test
    fun `陌生通话 offer 重发两次都稳定忙拒绝`() {
        // 准入判定无状态：同一拒绝信令重发，结论不变。
        repeat(2) {
            assertEquals(
                Decision.BUSY_REJECT,
                CallSignalingAdmissionPolicy.admit(
                    isGroupCall = false,
                    expectedContactId = "a",
                    fromUserId = "a",
                    activeCallId = "call-1",
                    incomingCallId = "call-2",
                    activeGroupId = "",
                    incomingGroupId = "",
                    activeMembers = emptyList(),
                    incomingMembers = emptyList(),
                    signalType = "offer",
                ),
            )
        }
    }

    @Test
    fun `切换账号后旧联系人来信被丢弃`() {
        // expectedContactId 跟随当前账号：换账号后旧联系人的 sender 不再可信。
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "b",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-9",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "b",
                fromUserId = "b",
                activeCallId = "",
                incomingCallId = "call-9",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }
}

class CallSignalingIdempotencyStoreTest {

    @Test
    fun `prefers idempotency key and bounds size`() {
        val store = CallSignalingIdempotencyStore(maxSize = 2)
        assertTrue(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertFalse(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
        assertTrue(store.remember("c", "u", "offer", "p2", idempotencyKey = "k2"))
        assertTrue(store.remember("c", "u", "offer", "p3", idempotencyKey = "k3"))
        // oldest (k1) evicted
        assertTrue(store.remember("c", "u", "offer", "p1", idempotencyKey = "k1"))
    }

    @Test
    fun `falls back to full payload key`() {
        val store = CallSignalingIdempotencyStore()
        assertTrue(store.remember("c", "u", "ICE-CANDIDATE", "mid|0|cand-a"))
        assertFalse(store.remember("c", "u", "ice-candidate", "mid|0|cand-a"))
        assertTrue(store.remember("c", "u", "ice-candidate", "mid|0|cand-b"))
    }
}

class CallSignalingOutboundCursorTest {

    @Test
    fun `begin resets sequence and builds stable idempotency keys`() {
        val cursor = CallSignalingOutboundCursor()
        cursor.begin(epochSeed = 42L)
        val first = cursor.next("call-a", "OFFER")
        val second = cursor.next("call-a", "ice-candidate")
        assertEquals(42L, first.epoch)
        assertEquals(1L, first.sequence)
        assertEquals("call-a|42|1|offer", first.idempotencyKey)
        assertEquals(42L, second.epoch)
        assertEquals(2L, second.sequence)
        assertEquals("call-a|42|2|ice-candidate", second.idempotencyKey)
    }
}

class GroupCallCapabilitiesTest {

    @Test
    fun `mesh size and unimplemented features are explicit`() {
        assertTrue(GroupCallCapabilities.canStartMesh(2))
        assertTrue(GroupCallCapabilities.canStartMesh(GroupCallCapabilities.MAX_MESH_MEMBERS))
        assertFalse(GroupCallCapabilities.canStartMesh(1))
        assertFalse(GroupCallCapabilities.canStartMesh(GroupCallCapabilities.MAX_MESH_MEMBERS + 1))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.SFU))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.SCREEN_SHARE))
        assertTrue(GroupCallCapabilities.isUnimplemented(Feature.CALL_RECORDING))
        assertFalse(GroupCallCapabilities.SFU_SUPPORTED)
        assertFalse(GroupCallCapabilities.SCREEN_SHARE_SUPPORTED)
        assertFalse(GroupCallCapabilities.CALL_RECORDING_SUPPORTED)
    }

    @Test
    fun `blank group ids bypass member mismatch`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "b",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "g1",
                activeMembers = listOf("a"),
                incomingMembers = listOf("a", "b", "c"),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `group member order does not matter`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("b", "a"),
                signalType = "answer",
            ),
        )
    }

    @Test
    fun `same epoch higher sequence is accepted`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 1,
                incomingSequence = 3,
                lastAcceptedCursor = Cursor(1, 2),
            ),
        )
    }

    @Test
    fun `direct call with blank expected contact accepts any sender`() {
        assertEquals(
            Decision.ACCEPT,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = false,
                expectedContactId = "",
                fromUserId = "stranger",
                activeCallId = "",
                incomingCallId = "call-9",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
            ),
        )
    }

    @Test
    fun `stale cursor drops even when call id and metadata match`() {
        assertEquals(
            Decision.DROP,
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = "answer",
                incomingEpoch = 1,
                incomingSequence = 1,
                lastAcceptedCursor = Cursor(1, 2),
            ),
        )
    }
}
