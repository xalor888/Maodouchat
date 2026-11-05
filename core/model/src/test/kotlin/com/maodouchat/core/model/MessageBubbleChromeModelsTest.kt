package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleChromeModelsTest {

    @Test
    fun `reply preview keeps fields and copy semantics`() {
        val preview = ReplyPreview("Alice", "hi")
        assertEquals("Alice", preview.senderName)
        assertEquals("bye", preview.copy(preview = "bye").preview)
    }
}
