package com.maodouchat.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageEditWindowPolicyTest {
    @Test
    fun `location supports full live sharing duration`() {
        assertEquals(
            MessageRepository.LIVE_LOCATION_EDIT_WINDOW_MS,
            MessageRepository.editWindowMsForType("LOCATION")
        )
    }

    @Test
    fun `ordinary messages keep five minute edit window`() {
        assertEquals(MessageRepository.EDIT_WINDOW_MS, MessageRepository.editWindowMsForType("TEXT"))
        assertEquals(MessageRepository.EDIT_WINDOW_MS, MessageRepository.editWindowMsForType("MARKDOWN"))
    }
}
