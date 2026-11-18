package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class MediaExportUseCaseModelsTest {

    @Test
    fun `export request defaults chooser title to empty`() {
        val request = MediaExportRequest(
            rawUri = "u", mimeType = "image/png",
            displayName = "d", isSecretChat = false,
        )
        assertEquals("", request.chooserTitle)
        assertEquals("u", request.rawUri)
    }

    @Test
    fun `export failure carries cause or null`() {
        val cause = IllegalStateException("boom")
        assertEquals("boom", MediaExportResult.Failure(cause).cause?.message)
        assertNull(MediaExportResult.Failure().cause)
    }
}
