package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Lease
import com.maodouchat.core.model.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatSessionCipherControllerTest {

    private class FakeOccupancy {
        var nextGeneration = 0L
        val acquired = mutableListOf<String>()
        val occupied = mutableListOf<Triple<Lease, String, String?>>()
        val occupiedUpdatePeer = mutableListOf<Boolean>()
        val released = mutableListOf<Lease>()

        fun acquire(chatId: String): Lease {
            acquired += chatId
            return Lease(++nextGeneration)
        }

        fun occupy(lease: Lease, chatId: String, peerId: String?, updatePeer: Boolean) {
            occupied += Triple(lease, chatId, peerId)
            occupiedUpdatePeer += updatePeer
        }

        fun release(lease: Lease) {
            released += lease
        }
    }

    private class Harness(
        initialChatId: String = "",
        var cached: Chat? = null,
    ) {
        val occupancy = FakeOccupancy()
        var currentUserId = "me"
        val controller = ChatSessionCipherController(
            initialChatId = initialChatId,
            getChatById = { cached },
            acquireOccupancy = occupancy::acquire,
            occupyOccupancy = occupancy::occupy,
            releaseOccupancy = occupancy::release,
            currentUserId = { currentUserId },
        )
    }

    @Test
    fun `occupy acquires lease once and reuses it`() {
        val h = Harness()
        h.controller.occupySessionCipher("c1", peerUserId = "u1", updatePeer = true)
        h.controller.occupySessionCipher("c1", peerUserId = "u1", updatePeer = true)
        assertEquals(listOf("c1"), h.occupancy.acquired)
        assertEquals(2, h.occupancy.occupied.size)
        assertEquals(1L, h.occupancy.occupied[0].first.generation)
        assertEquals("u1", h.occupancy.occupied[0].third)
        assertEquals(listOf(true, true), h.occupancy.occupiedUpdatePeer)
    }

    @Test
    fun `release calls release port with the lease and second release is a no-op`() {
        val h = Harness()
        h.controller.occupySessionCipher("c1")
        h.controller.releaseSessionCipher()
        h.controller.releaseSessionCipher()
        assertEquals(1, h.occupancy.released.size)
        assertEquals(1L, h.occupancy.released[0].generation)
    }

    @Test
    fun `occupy after release re-acquires a fresh lease`() {
        val h = Harness()
        h.controller.occupySessionCipher("c1")
        h.controller.releaseSessionCipher()
        h.controller.occupySessionCipher("c1")
        assertEquals(listOf("c1", "c1"), h.occupancy.acquired)
        assertEquals(2L, h.occupancy.occupied[1].first.generation)
    }

    @Test
    fun `blank target chat is a no-op`() {
        val h = Harness()
        h.controller.occupySessionCipher("  ")
        assertTrue(h.occupancy.acquired.isEmpty())
        assertTrue(h.occupancy.occupied.isEmpty())
    }

    @Test
    fun `initial chat acquires a lease on construction`() {
        val h = Harness(initialChatId = "c0")
        assertEquals(listOf("c0"), h.occupancy.acquired)
    }

    @Test
    fun `pin from cache occupies group without peer`() = runTest {
        val h = Harness(cached = Chat(id = "g1", isGroup = true))
        h.controller.pinSessionCipherPeerFromCache("g1")
        assertEquals(1, h.occupancy.occupied.size)
        assertEquals("g1", h.occupancy.occupied[0].second)
        assertEquals(null, h.occupancy.occupied[0].third)
        assertEquals(listOf(true), h.occupancy.occupiedUpdatePeer)
    }

    @Test
    fun `pin from cache occupies 1-1 peer other than self`() = runTest {
        val h = Harness(
            cached = Chat(
                id = "c1",
                participants = listOf(User("me", "Me"), User("peer", "Peer")),
            )
        )
        h.controller.pinSessionCipherPeerFromCache("c1")
        assertEquals(1, h.occupancy.occupied.size)
        assertEquals("peer", h.occupancy.occupied[0].third)
        assertEquals(listOf(true), h.occupancy.occupiedUpdatePeer)
    }

    @Test
    fun `pin from cache is a no-op when chat is missing`() = runTest {
        val h = Harness(cached = null)
        h.controller.pinSessionCipherPeerFromCache("c1")
        assertTrue(h.occupancy.acquired.isEmpty())
        assertTrue(h.occupancy.occupied.isEmpty())
    }

    @Test
    fun `pin from cache skips when only self participates`() = runTest {
        val h = Harness(
            cached = Chat(id = "c1", participants = listOf(User("me", "Me")))
        )
        h.controller.pinSessionCipherPeerFromCache("c1")
        assertTrue(h.occupancy.occupied.isEmpty())
    }
}
