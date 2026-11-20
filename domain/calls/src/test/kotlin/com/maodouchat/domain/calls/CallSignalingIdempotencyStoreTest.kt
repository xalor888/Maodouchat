package com.maodouchat.domain.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
