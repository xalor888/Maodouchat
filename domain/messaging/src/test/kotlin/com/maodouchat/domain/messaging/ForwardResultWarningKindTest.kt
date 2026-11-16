package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForwardResultWarningKindTest {

    @Test
    fun `all succeeded returns forwarded`() {
        assertEquals(
            ForwardResultWarningKind.FORWARDED,
            forwardResultWarningKind(true, false, false, false, false)
        )
    }

    @Test
    fun `pin locked returns secret blocked`() {
        assertEquals(
            ForwardResultWarningKind.SECRET_BLOCKED,
            forwardResultWarningKind(true, true, true, false, false)
        )
    }

    @Test
    fun `forbidden returns secret blocked`() {
        assertEquals(
            ForwardResultWarningKind.SECRET_BLOCKED,
            forwardResultWarningKind(true, true, false, true, false)
        )
    }

    @Test
    fun `reliable attachment failed returns attachment upload failed`() {
        assertEquals(
            ForwardResultWarningKind.ATTACHMENT_UPLOAD_FAILED,
            forwardResultWarningKind(true, true, false, false, true)
        )
    }

    @Test
    fun `other failure returns refresh failed cached`() {
        assertEquals(
            ForwardResultWarningKind.REFRESH_FAILED_CACHED,
            forwardResultWarningKind(true, true, false, false, false)
        )
    }

    @Test
    fun `empty results without failure returns forwarded`() {
        assertEquals(
            ForwardResultWarningKind.FORWARDED,
            forwardResultWarningKind(false, false, false, false, false)
        )
    }
}
