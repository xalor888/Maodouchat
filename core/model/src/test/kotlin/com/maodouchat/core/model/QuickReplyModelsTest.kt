package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReplyModelsTest {

    @Test
    fun `sync verdict carries text and owner`() {
        val verdict = SyncVerdict.Allowed(text = "hi", ownerUserId = "u1")
        assertEquals("hi", verdict.text)
        assertEquals("u1", verdict.ownerUserId)
        assertTrue(SyncVerdict.Rejected("empty_text") is SyncVerdict)
    }

    @Test
    fun `chat gate verdict rejected carries reason`() {
        val verdict = ChatGateVerdict.Rejected("session_changed")
        assertEquals("session_changed", verdict.reason)
        assertTrue(ChatGateVerdict.Allowed is ChatGateVerdict)
    }
}
