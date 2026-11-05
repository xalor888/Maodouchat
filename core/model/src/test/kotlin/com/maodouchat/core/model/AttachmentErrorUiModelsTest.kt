package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentErrorUiModelsTest {

    @Test
    fun `error kinds cover all failure shapes`() {
        assertEquals(5, AttachmentErrorKind.entries.size)
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorKind.valueOf("TOO_LARGE"))
        assertEquals(AttachmentErrorKind.FALLBACK, AttachmentErrorKind.valueOf("FALLBACK"))
    }
}
