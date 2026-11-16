package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaSendModeKindTest {

    @Test
    fun `view once wins over spoiler`() {
        assertEquals(MediaSendModeKind.VIEW_ONCE, mediaSendMode(viewOnce = true, spoiler = true))
        assertEquals(MediaSendModeKind.VIEW_ONCE, mediaSendMode(viewOnce = true, spoiler = false))
    }

    @Test
    fun `spoiler wins over normal send`() {
        assertEquals(MediaSendModeKind.SPOILER, mediaSendMode(viewOnce = false, spoiler = true))
        assertEquals(MediaSendModeKind.NORMAL, mediaSendMode(viewOnce = false, spoiler = false))
    }
}
