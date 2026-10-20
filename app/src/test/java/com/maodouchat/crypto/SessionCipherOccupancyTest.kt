package com.maodouchat.crypto

import com.maodouchat.MaodouchatApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCipherOccupancyTest {

    @Test
    fun skipWhenPeerOccupiedEvenIfChatDiffers() {
        SessionCipherOccupancy.occupyPeer("alice")
        try {
            assertTrue(SessionCipherOccupancy.shouldSkipSessionCipher("direct-with-alice", "alice"))
            assertTrue(SessionCipherOccupancy.shouldSkipSessionCipher("group-sk-wrap", "alice"))
            assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("other", "bob"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun blankPeerNeverOccupies() {
        SessionCipherOccupancy.occupyPeer("   ")
        assertFalse(SessionCipherOccupancy.isPeerOccupied("   "))
        assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
        assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("c1", "alice"))
    }

    @Test
    fun occupyWithoutUpdatePeerKeepsExistingPeer() {
        SessionCipherOccupancy.occupyPeer("alice")
        try {
            SessionCipherOccupancy.occupy("direct-1")
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            SessionCipherOccupancy.occupy("group-1", peerUserId = null, updatePeer = true)
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun blankPeerWithUpdatePeerDoesNotClearExisting() {
        SessionCipherOccupancy.occupyPeer("alice")
        try {
            SessionCipherOccupancy.occupy("direct-1", peerUserId = "", updatePeer = true)
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            SessionCipherOccupancy.occupy("direct-1", peerUserId = "   ", updatePeer = true)
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun staleLeaseCannotReleaseNewConversation() {
        val old = SessionCipherOccupancy.acquire("old-chat")
        val current = SessionCipherOccupancy.acquire("new-chat")

        assertFalse(SessionCipherOccupancy.release(old))
        assertTrue(SessionCipherOccupancy.isChatOccupied("new-chat"))
        assertTrue(SessionCipherOccupancy.occupy(current, "new-chat", "alice", updatePeer = true))
        assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))

        assertTrue(SessionCipherOccupancy.release(current))
        assertFalse(SessionCipherOccupancy.isChatOccupied("new-chat"))
        assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
    }

    @Test
    fun staleLeaseOccupyIsRejectedAndPeerUnchanged() {
        val old = SessionCipherOccupancy.acquire("chat-a")
        assertTrue(SessionCipherOccupancy.occupy(old, "chat-a", "alice", updatePeer = true))
        val current = SessionCipherOccupancy.acquire("chat-b")
        try {
            assertFalse(SessionCipherOccupancy.occupy(old, "chat-a", "mallory", updatePeer = true))
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("mallory"))
        } finally {
            SessionCipherOccupancy.release(current)
        }
    }

    @Test
    fun blankChatIdOccupyLeavesMarkersUntouched() {
        SessionCipherOccupancy.occupy("chat-a")
        try {
            SessionCipherOccupancy.occupy("   ")
            assertTrue(SessionCipherOccupancy.isChatOccupied("chat-a"))
            assertFalse(SessionCipherOccupancy.isChatOccupied("  "))
            assertFalse(SessionCipherOccupancy.isChatOccupied(""))
        } finally {
            SessionCipherOccupancy.release("chat-a")
        }
    }

    @Test
    fun legacyReleaseOnlyClearsMatchingChat() {
        SessionCipherOccupancy.occupy("chat-a")
        try {
            assertFalse(SessionCipherOccupancy.release("chat-b"))
            assertTrue(SessionCipherOccupancy.isChatOccupied("chat-a"))
            assertTrue(SessionCipherOccupancy.release("chat-a"))
            assertFalse(SessionCipherOccupancy.isChatOccupied("chat-a"))
        } finally {
            SessionCipherOccupancy.release("chat-a")
        }
    }

    @Test
    fun legacyReleaseOnEmptyStateIsNoop() {
        assertFalse(SessionCipherOccupancy.release("ghost"))
    }

    @Test
    fun occupyPeerNullClearsPeer() {
        SessionCipherOccupancy.occupyPeer("alice")
        try {
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
        assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
    }

    @Test
    fun acquireSetsMarkersAndDoubleReleaseIsRejected() {
        val lease = SessionCipherOccupancy.acquire("cov-a")
        try {
            assertTrue(SessionCipherOccupancy.isChatOccupied("cov-a"))
            assertTrue(SessionCipherOccupancy.release(lease))
            assertFalse(SessionCipherOccupancy.isChatOccupied("cov-a"))
            assertFalse(SessionCipherOccupancy.release(lease))
        } finally {
            SessionCipherOccupancy.release("cov-a")
        }
    }

    @Test
    fun occupyTrimsChatIdBeforeMarking() {
        SessionCipherOccupancy.occupy("  cov-b  ")
        try {
            assertTrue(SessionCipherOccupancy.isChatOccupied("cov-b"))
            assertFalse(SessionCipherOccupancy.isChatOccupied("  cov-b  "))
            assertTrue(SessionCipherOccupancy.release("cov-b"))
        } finally {
            SessionCipherOccupancy.release("cov-b")
        }
    }

    @Test
    fun occupyPeerTrimsButBlankClears() {
        SessionCipherOccupancy.occupyPeer("  bob  ")
        try {
            assertTrue(SessionCipherOccupancy.isPeerOccupied("bob"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("  bob  "))
            SessionCipherOccupancy.occupyPeer("   ")
            assertFalse(SessionCipherOccupancy.isPeerOccupied("bob"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun shouldSkipSessionCipherIgnoresBlankIds() {
        SessionCipherOccupancy.occupyPeer(null)
        SessionCipherOccupancy.occupy("cov-d")
        try {
            assertTrue(SessionCipherOccupancy.shouldSkipSessionCipher("cov-d", "nobody"))
            assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("", ""))
            assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("", "nobody"))
            assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("other", "nobody"))
        } finally {
            SessionCipherOccupancy.release("cov-d")
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun newAcquireSupersedesOldMarkersButKeepsPeerUntilRelease() {
        val old = SessionCipherOccupancy.acquire("cov-e")
        assertTrue(SessionCipherOccupancy.occupy(old, "cov-e", "eve", updatePeer = true))
        val current = SessionCipherOccupancy.acquire("cov-f")
        try {
            assertFalse(SessionCipherOccupancy.isChatOccupied("cov-e"))
            assertTrue(SessionCipherOccupancy.isChatOccupied("cov-f"))
            assertTrue(SessionCipherOccupancy.isPeerOccupied("eve"))
            assertTrue(SessionCipherOccupancy.release(current))
            assertFalse(SessionCipherOccupancy.isChatOccupied("cov-f"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("eve"))
        } finally {
            SessionCipherOccupancy.release(current)
            SessionCipherOccupancy.occupyPeer(null)
        }
    }


    @Test
    fun doubleAcquireKeepsOnlyNewLeaseAlive() {
        val stale = SessionCipherOccupancy.acquire("occ-a")
        val current = SessionCipherOccupancy.acquire("occ-b")
        try {
            assertFalse(SessionCipherOccupancy.release(stale))
            assertTrue(SessionCipherOccupancy.isChatOccupied("occ-b"))
            assertTrue(SessionCipherOccupancy.release(current))
            assertFalse(SessionCipherOccupancy.isChatOccupied("occ-b"))
        } finally {
            SessionCipherOccupancy.release(current)
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun occupyWithNullPeerAndUpdateClearsPeer() {
        SessionCipherOccupancy.occupyPeer("alice")
        SessionCipherOccupancy.occupy("occ-c", peerUserId = null, updatePeer = true)
        try {
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertTrue(SessionCipherOccupancy.isChatOccupied("occ-c"))
        } finally {
            SessionCipherOccupancy.release("occ-c")
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun peerMatchIsCaseSensitive() {
        SessionCipherOccupancy.occupyPeer("alice")
        try {
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("Alice"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("ALICE"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun legacyReleaseBlankChatIdIsRejectedAndChatKept() {
        SessionCipherOccupancy.occupy("occ-d")
        try {
            assertFalse(SessionCipherOccupancy.release("   "))
            assertTrue(SessionCipherOccupancy.isChatOccupied("occ-d"))
        } finally {
            SessionCipherOccupancy.release("occ-d")
        }
    }

    @Test
    fun occupyPeerTrimsNewlineChars() {
        SessionCipherOccupancy.occupyPeer("\nalice\n")
        try {
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("\nalice\n"))
        } finally {
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun legacyReleaseTrimsChatIdBeforeMatching() {
        SessionCipherOccupancy.occupy("occ-h")
        try {
            assertTrue(SessionCipherOccupancy.release("  occ-h  "))
            assertFalse(SessionCipherOccupancy.isChatOccupied("occ-h"))
        } finally {
            SessionCipherOccupancy.release("occ-h")
        }
    }

    @Test
    fun legacyReleaseClearsPeerTogetherWithItsChatDetail() {
        SessionCipherOccupancy.occupy("occ-f", "alice", updatePeer = true)
        try {
            assertTrue(SessionCipherOccupancy.release("occ-f"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.release("occ-f")
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun staleLeaseReleaseLeavesNewConversationIntact() {
        val old = SessionCipherOccupancy.acquire("occ-old")
        assertTrue(SessionCipherOccupancy.occupy(old, "occ-old", "alice", updatePeer = true))
        val current = SessionCipherOccupancy.acquire("occ-new")
        try {
            assertFalse(SessionCipherOccupancy.release(old))
            assertTrue(SessionCipherOccupancy.isChatOccupied("occ-new"))
            assertTrue(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertTrue(SessionCipherOccupancy.release(current))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.release(current)
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun refreshActiveChatOpenedAtNeverOverwritesExisting() {
        MaodouchatApp.activeChatOpenedAtMs = 12345L
        try {
            SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()
            assertEquals(12345L, MaodouchatApp.activeChatOpenedAtMs)
        } finally {
            MaodouchatApp.activeChatOpenedAtMs = 0L
        }
    }

    @Test
    fun occupyWithLeaseAfterLegacyReleaseIsRejected() {
        val lease = SessionCipherOccupancy.acquire("occ-x")
        try {
            assertTrue(SessionCipherOccupancy.release("occ-x"))
            assertFalse(SessionCipherOccupancy.occupy(lease, "occ-x", "alice", updatePeer = true))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
        } finally {
            SessionCipherOccupancy.release("occ-x")
            SessionCipherOccupancy.occupyPeer(null)
        }
    }

    @Test
    fun occupyWithBlankChatIdStillSucceedsWhenLeaseIsCurrent() {
        val lease = SessionCipherOccupancy.acquire("occ-y")
        try {
            // 空 chatId 不写标记，但租约校验已过，返回 true
            assertTrue(SessionCipherOccupancy.occupy(lease, "   "))
            assertTrue(SessionCipherOccupancy.isChatOccupied("occ-y"))
        } finally {
            SessionCipherOccupancy.release(lease)
        }
    }

    @Test
    fun shouldSkipSessionCipherSkipsOnChatEvenWithBlankPeer() {
        SessionCipherOccupancy.occupy("occ-z")
        try {
            assertTrue(SessionCipherOccupancy.shouldSkipSessionCipher("occ-z", ""))
            assertTrue(SessionCipherOccupancy.shouldSkipSessionCipher("occ-z", "   "))
            assertFalse(SessionCipherOccupancy.shouldSkipSessionCipher("other", ""))
        } finally {
            SessionCipherOccupancy.release("occ-z")
        }
    }

    @Test
    fun refreshActiveChatOpenedAtSetsOnceWhenZero() {
        MaodouchatApp.activeChatOpenedAtMs = 0L
        try {
            SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()
            val first = MaodouchatApp.activeChatOpenedAtMs
            assertTrue(first > 0L)
            SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()
            assertEquals(first, MaodouchatApp.activeChatOpenedAtMs)
        } finally {
            MaodouchatApp.activeChatOpenedAtMs = 0L
        }
    }

    @Test
    fun releaseLeaseClearsOpenedAtAndAllMarkers() {
        val lease = SessionCipherOccupancy.acquire("occ-w")
        assertTrue(SessionCipherOccupancy.occupy(lease, "occ-w", "alice", updatePeer = true))
        MaodouchatApp.activeChatOpenedAtMs = 777L
        try {
            assertTrue(SessionCipherOccupancy.release(lease))
            assertFalse(SessionCipherOccupancy.isChatOccupied("occ-w"))
            assertFalse(SessionCipherOccupancy.isPeerOccupied("alice"))
            assertEquals(0L, MaodouchatApp.activeChatOpenedAtMs)
        } finally {
            SessionCipherOccupancy.release(lease)
            SessionCipherOccupancy.occupyPeer(null)
            MaodouchatApp.activeChatOpenedAtMs = 0L
        }
    }

    @Test
    fun acquireWithBlankChatIdClaimsLeaseWithoutMarkers() {
        val lease = SessionCipherOccupancy.acquire("   ")
        try {
            assertFalse(SessionCipherOccupancy.isChatOccupied(""))
            assertFalse(SessionCipherOccupancy.isChatOccupied("   "))
            assertTrue(SessionCipherOccupancy.release(lease))
            assertFalse(SessionCipherOccupancy.isChatOccupied(""))
        } finally {
            SessionCipherOccupancy.release(lease)
            SessionCipherOccupancy.release("   ")
        }
    }
}
