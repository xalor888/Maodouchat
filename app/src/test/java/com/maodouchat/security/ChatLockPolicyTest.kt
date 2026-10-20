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

    @Test
    fun `empty string owner fails closed against a live user`() {
        // 空串不是 null：owner != live，直接判锁定。
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "", liveUserId = "u1"))
    }

    @Test
    fun `chat id match is case sensitive`() {
        assertFalse(ChatLockPolicy.isUnlocked("C1", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `owner match is case sensitive`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "U1", liveUserId = "u1"))
    }

    @Test
    fun `null live user with null owner still honors the set`() {
        // 两边都没记录归属时，只看解锁集合。
        assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = null, liveUserId = null))
    }

    @Test
    fun `missing chat id stays locked even without an owner`() {
        assertFalse(ChatLockPolicy.isUnlocked("c9", setOf("c1"), ownerUserId = null, liveUserId = "u1"))
    }

    @Test
    fun `blank chat id fails closed against a populated unlock set`() {
        assertFalse(ChatLockPolicy.isUnlocked("", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `blank live user id fails closed`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = ""))
    }

    @Test
    fun `set membership is exact with no whitespace tolerance`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1 ", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
        assertFalse(ChatLockPolicy.isUnlocked(" c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `account switch between calls flips the verdict`() {
        assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u2"))
    }

    @Test
    fun `decision is stable across repeated calls`() {
        repeat(3) {
            assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "u1"))
        }
    }

    @Test
    fun `empty unlock set keeps everything locked`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1", emptySet(), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `blank entry in the set never unlocks a blank chat id`() {
        // 空串 chatId 先判否：即使解锁集合里真有空串也不放行。
        assertFalse(ChatLockPolicy.isUnlocked("", setOf("", "c1"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `owner flipping to blank string locks mid-session`() {
        assertTrue(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = null, liveUserId = "u1"))
        // 空串归属 ≠ null 归属：不是"没记录"，而是"对不上"。
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "", liveUserId = "u1"))
    }

    @Test
    fun `membership is exact among multiple entries`() {
        assertTrue(ChatLockPolicy.isUnlocked("c3", setOf("c1", "c3"), ownerUserId = "u1", liveUserId = "u1"))
        assertFalse(ChatLockPolicy.isUnlocked("c2", setOf("c1", "c3"), ownerUserId = "u1", liveUserId = "u1"))
    }

    @Test
    fun `live user case mismatch against owner fails closed`() {
        assertFalse(ChatLockPolicy.isUnlocked("c1", setOf("c1"), ownerUserId = "u1", liveUserId = "U1"))
    }
}
