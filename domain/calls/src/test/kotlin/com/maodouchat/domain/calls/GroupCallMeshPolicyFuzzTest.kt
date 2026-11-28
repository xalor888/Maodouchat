package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupCallMeshPolicyFuzzTest {

    private val random = Random(2026112244L)

    private val idPool = listOf(
        "", " ", "\t", "a", "b", "alice", "bob", "Alice", "A", "aa",
        "a-b", "user-1", "user-2", "user-10", "  alice", "bob ",
    )

    // 活跃边只认三种连接中状态；其余一律不活跃。
    private fun oracleIsActive(state: GroupPeerConnectionState): Boolean =
        state == GroupPeerConnectionState.CONNECTING ||
            state == GroupPeerConnectionState.CONNECTED ||
            state == GroupPeerConnectionState.RECONNECTING

    // 只有字典序较小的一方建边：双方非空且 self 严格小于 peer。
    private fun oracleShouldInitiate(selfUserId: String, peerUserId: String): Boolean =
        selfUserId.isNotBlank() && peerUserId.isNotBlank() && selfUserId < peerUserId

    @Test
    fun `isActive matches the oracle for every peer state`() {
        for (state in GroupPeerConnectionState.entries) {
            assertEquals(oracleIsActive(state), GroupCallPolicy.isActive(state), "state=$state")
        }
    }

    @Test
    fun `isActive pins the active set`() {
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.CONNECTING))
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.CONNECTED))
        assertTrue(GroupCallPolicy.isActive(GroupPeerConnectionState.RECONNECTING))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.DISCONNECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.FAILED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.REJECTED))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.BUSY))
        assertFalse(GroupCallPolicy.isActive(GroupPeerConnectionState.NO_ANSWER))
    }

    @Test
    fun `shouldInitiateMeshEdge matches the oracle for random ids`() {
        repeat(300) {
            val self = idPool.random(random)
            val peer = idPool.random(random)
            assertEquals(
                oracleShouldInitiate(self, peer),
                GroupCallPolicy.shouldInitiateMeshEdge(self, peer),
                "self=$self peer=$peer",
            )
        }
    }

    @Test
    fun `shouldInitiateMeshEdge pins lexicographic order`() {
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("alice", "bob"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("bob", "alice"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("alice", "alice"))
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("user-10", "user-2"))
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("a", "aa"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("aa", "a"))
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("A", "a"))
    }

    @Test
    fun `shouldInitiateMeshEdge pins blank ids`() {
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("", "bob"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("alice", ""))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge(" ", "bob"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("alice", "\t"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge(" ", " "))
        // 前后空白不算空白 id：带内容的 id 仍按字典序比较。
        assertTrue(GroupCallPolicy.shouldInitiateMeshEdge("  alice", "bob"))
        assertFalse(GroupCallPolicy.shouldInitiateMeshEdge("bob ", "bob"))
    }
}
