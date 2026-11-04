package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PrefetchMessageTest {

    @Test
    fun `fields are carried`() {
        val message = PrefetchMessage("m1", "me", true)
        assertEquals("m1", message.id)
        assertEquals("me", message.senderId)
        assertEquals(true, message.eligibleForGroupReadCount)
    }

    @Test
    fun `copy keeps other fields`() {
        val copied = PrefetchMessage("m1", "me", true).copy(eligibleForGroupReadCount = false)
        assertEquals("m1", copied.id)
        assertEquals(false, copied.eligibleForGroupReadCount)
    }

    @Test
    fun `different ids are not equal`() {
        assertNotEquals(PrefetchMessage("m1", "me", true), PrefetchMessage("m2", "me", true))
    }
}
