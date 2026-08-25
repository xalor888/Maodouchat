package com.maodouchat.crypto

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
}
