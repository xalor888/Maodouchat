package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.Test

class MessageBubbleChromeModelsTest {

    @Test
    fun `reply preview keeps fields and copy semantics`() {
        val preview = ReplyPreview("Alice", "hi")
        assertEquals("Alice", preview.senderName)
        assertEquals("bye", preview.copy(preview = "bye").preview)
    }
}
