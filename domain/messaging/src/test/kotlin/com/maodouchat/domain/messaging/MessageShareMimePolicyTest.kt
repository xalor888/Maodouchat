package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageShareMimePolicyTest {

    @Test
    fun imageReturnsImageMime() {
        assertEquals("image/*", MessageShareMimePolicy.shareMimeType(MessageType.IMAGE))
    }

    @Test
    fun gifReturnsImageMime() {
        assertEquals("image/*", MessageShareMimePolicy.shareMimeType(MessageType.GIF))
    }

    @Test
    fun videoReturnsVideoMime() {
        assertEquals("video/*", MessageShareMimePolicy.shareMimeType(MessageType.VIDEO))
    }

    @Test
    fun fileReturnsOctetStream() {
        assertEquals("application/octet-stream", MessageShareMimePolicy.shareMimeType(MessageType.FILE))
    }

    @Test
    fun textReturnsNull() {
        assertNull(MessageShareMimePolicy.shareMimeType(MessageType.TEXT))
    }

    @Test
    fun voiceReturnsNull() {
        assertNull(MessageShareMimePolicy.shareMimeType(MessageType.VOICE))
    }

    @Test
    fun stickerReturnsNull() {
        assertNull(MessageShareMimePolicy.shareMimeType(MessageType.STICKER))
    }

    @Test
    fun locationReturnsNull() {
        assertNull(MessageShareMimePolicy.shareMimeType(MessageType.LOCATION))
    }
}
