package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AttachmentUploadStatusResponse
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AttachmentUploadStatusPolicyTest {

    private fun status(
        id: String = "att_" + "a".repeat(20),
        cipherSha256: String = "abc123",
        cipherSize: Long = 100L,
        uploadedBytes: Long = 50L,
        status: String = "UPLOADING",
        complete: Boolean = false
    ) = AttachmentUploadStatusResponse(
        id = id,
        cipherSha256 = cipherSha256,
        cipherSize = cipherSize,
        uploadedBytes = uploadedBytes,
        status = status,
        expiresAt = 0L,
        complete = complete
    )

    @Test
    fun `matching status passes`() {
        AttachmentUploadStatusPolicy.validate(status(), "ABC123", 100L)
    }

    @Test
    fun `bad attachment id fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(status(id = "bad-id"), "abc123", 100L)
        }
    }

    @Test
    fun `hash mismatch fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(status(), "deadbeef", 100L)
        }
    }

    @Test
    fun `size mismatch fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(status(), "abc123", 101L)
        }
    }

    @Test
    fun `uploaded bytes out of range fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(status(uploadedBytes = 101L), "abc123", 100L)
        }
    }

    @Test
    fun `unknown status fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(status(status = "FAILED"), "abc123", 100L)
        }
    }

    @Test
    fun `complete without full upload fails`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploadStatusPolicy.validate(
                status(uploadedBytes = 50L, status = "COMMITTED", complete = true),
                "abc123", 100L
            )
        }
    }
}
