package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
