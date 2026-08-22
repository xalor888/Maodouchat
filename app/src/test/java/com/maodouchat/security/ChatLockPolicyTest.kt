package com.maodouchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLockPolicyTest {
    @Test
    fun `blank chat id is never treated as unlocked`() {
        assertFalse(ChatLockPolicy.isUnlocked("", setOf(""), ownerUserId = "u1", liveUserId = "u1"))
        assertFalse(ChatLockPolicy.isUnlocked("  ", setOf("  "), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `matching owner and live user honors the unlock set`() {
        assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
        assertFalse(ChatLockPolicy.isUnlocked("c2", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `account mismatch fails closed`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u2"))
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = null))
    }

    @Test
    fun `unset owner still honors membership for the current process`() {
        assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = null, liveUserId = "u1"))
    }
}
