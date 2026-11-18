package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
