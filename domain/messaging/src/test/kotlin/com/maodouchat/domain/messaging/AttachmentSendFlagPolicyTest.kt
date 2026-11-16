package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Flag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AttachmentSendFlagPolicyTest {

    @Test
    fun `send kinds map to flag and message`() {
        assertEquals(
            Flag("image_send_enabled", true) to "image_send_disabled",
            AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.IMAGE),
        )
        assertEquals(
            Flag("video_send_enabled", true) to "video_send_disabled",
            AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.VIDEO),
        )
        assertEquals(
            Flag("gif_send_enabled", true) to "gif_send_disabled",
            AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.GIF),
        )
        assertEquals(
            Flag("file_share_enabled", true) to "file_share_disabled",
            AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.FILE),
        )
    }

    @Test
    fun `voice and other kinds require no flag`() {
        assertNull(AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.VOICE))
        assertNull(AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.STICKER))
        assertNull(AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.LOCATION))
        assertNull(AttachmentSendFlagPolicy.requiredFlag(AttachmentKind.CONTACT))
    }
}
