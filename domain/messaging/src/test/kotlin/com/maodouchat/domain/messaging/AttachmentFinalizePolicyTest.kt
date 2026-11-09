package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.MessageType
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentFinalizePolicyTest {

    @Test
    fun `eligible when type reliable upload complete sender and chat match`() {
        assertTrue(isFinalizeEligible(MessageType.IMAGE, true, "u1", "u1", "c1", "c1"))
    }

    @Test
    fun `ineligible when type not reliable`() {
        assertFalse(isFinalizeEligible(MessageType.TEXT, true, "u1", "u1", "c1", "c1"))
    }

    @Test
    fun `ineligible when upload not complete`() {
        assertFalse(isFinalizeEligible(MessageType.VIDEO, false, "u1", "u1", "c1", "c1"))
    }

    @Test
    fun `ineligible when sender is not owner`() {
        assertFalse(isFinalizeEligible(MessageType.FILE, true, "u2", "u1", "c1", "c1"))
    }

    @Test
    fun `ineligible when chat differs`() {
        assertFalse(isFinalizeEligible(MessageType.VOICE, true, "u1", "u1", "c1", "c2"))
    }

    @Test
    fun `unverified 400 404 410 require reupload`() {
        listOf(400, 404, 410).forEach { code ->
            assertEquals(
                AttachmentFinalizeFailure.ReuploadRequired,
                decideFinalizeFailure(false, ApiException(ApiFailureKind.HTTP, statusCode = code)),
            )
        }
    }

    @Test
    fun `verified 404 is not a stale upload`() {
        assertEquals(
            AttachmentFinalizeFailure.Failed,
            decideFinalizeFailure(true, ApiException(ApiFailureKind.HTTP, statusCode = 404)),
        )
    }

    @Test
    fun `retryable errors are transient`() {
        assertEquals(
            AttachmentFinalizeFailure.Transient,
            decideFinalizeFailure(false, IOException("eof")),
        )
        assertEquals(
            AttachmentFinalizeFailure.Transient,
            decideFinalizeFailure(true, ApiException(ApiFailureKind.HTTP, statusCode = 500)),
        )
    }

    @Test
    fun `other errors fail`() {
        assertEquals(
            AttachmentFinalizeFailure.Failed,
            decideFinalizeFailure(false, RuntimeException("boom")),
        )
    }

    @Test
    fun `failure code prefixes class name with SEND`() {
        assertEquals("SEND_RuntimeException", sendFailureCode(RuntimeException()))
        assertEquals("SEND_IllegalStateException", sendFailureCode(IllegalStateException()))
    }
}
