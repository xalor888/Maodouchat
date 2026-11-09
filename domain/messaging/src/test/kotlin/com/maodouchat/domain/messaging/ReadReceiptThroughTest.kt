package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadReceiptThroughTest {

    @Test
    fun `explicit through wins`() {
        assertEquals("msg-9", readReceiptThrough("msg-9", "msg-1"))
    }

    @Test
    fun `missing through falls back to target`() {
        assertEquals("msg-1", readReceiptThrough(null, "msg-1"))
    }
}
