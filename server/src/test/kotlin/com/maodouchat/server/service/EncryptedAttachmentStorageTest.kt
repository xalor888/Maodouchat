package com.maodouchat.server.service

import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedAttachmentStorageTest {

    private fun newId(): String = "att_" + UUID.randomUUID().toString().replace("-", "") + "xx"

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun withAttachment(block: (String) -> Unit) {
        val id = newId()
        try {
            block(id)
        } finally {
            EncryptedAttachmentStorage.delete(id)
        }
    }

    @Test
    fun `rejects invalid attachment ids`() {
        assertFailsWith<IllegalArgumentException> {
            EncryptedAttachmentStorage.createTempFile("att_short")
        }
        assertFailsWith<IllegalArgumentException> {
            EncryptedAttachmentStorage.createTempFile("../att_xxxxxxxxxxxxxxxxxxxx")
        }
        assertFailsWith<IllegalArgumentException> {
            EncryptedAttachmentStorage.createTempFile("att_xxxxxxxxxxxxxxxxxxxx/../x")
        }
        assertNull(EncryptedAttachmentStorage.resolve("att_short"))
        assertNull(EncryptedAttachmentStorage.sha256("../not-an-id"))
        assertFalse(EncryptedAttachmentStorage.delete("att_too_short"))
    }

    @Test
    fun `append chunk replay and content mismatch`() = withAttachment { id ->
        val first = "ciphertext-chunk-one".toByteArray()
        val second = "chunk-two".toByteArray()
        val accepted = EncryptedAttachmentStorage.appendChunk(id, 0, first, maxSize = 1024)
        assertEquals(
            EncryptedAttachmentStorage.AppendResult.Accepted(first.size.toLong(), replayed = false),
            accepted
        )
        val replay = EncryptedAttachmentStorage.appendChunk(id, 0, first, maxSize = 1024)
        assertEquals(
            EncryptedAttachmentStorage.AppendResult.Accepted(first.size.toLong(), replayed = true),
            replay
        )
        val mismatch = EncryptedAttachmentStorage.appendChunk(id, 0, "different-bytes!!!!".toByteArray(), maxSize = 1024)
        assertEquals(EncryptedAttachmentStorage.AppendResult.ContentMismatch, mismatch)

        val next = EncryptedAttachmentStorage.appendChunk(id, first.size.toLong(), second, maxSize = 1024)
        assertEquals(
            EncryptedAttachmentStorage.AppendResult.Accepted((first.size + second.size).toLong(), replayed = false),
            next
        )
        val gap = EncryptedAttachmentStorage.appendChunk(id, 999L, second, maxSize = 1024)
        assertTrue(gap is EncryptedAttachmentStorage.AppendResult.OffsetMismatch)
        assertEquals((first.size + second.size).toLong(), EncryptedAttachmentStorage.uploadedBytes(id))
    }

    @Test
    fun `finalize is idempotent when bin exists and part is gone`() = withAttachment { id ->
        val payload = "opaque-ciphertext-bytes".toByteArray()
        EncryptedAttachmentStorage.appendChunk(id, 0, payload, maxSize = 1024)
        val first = EncryptedAttachmentStorage.finalizeResumableUpload(id)
        assertNotNull(first)
        assertTrue(first.isFile)
        assertEquals(payload.size.toLong(), first.length())
        assertEquals(sha256Hex(payload), EncryptedAttachmentStorage.sha256(id))

        val again = EncryptedAttachmentStorage.finalizeResumableUpload(id)
        assertNotNull(again)
        assertEquals(first.canonicalFile, again.canonicalFile)

        val oneShot = EncryptedAttachmentStorage.finalizeUpload(id, EncryptedAttachmentStorage.createTempFile(id))
        assertEquals(first.canonicalFile, oneShot.canonicalFile)
        assertNull(EncryptedAttachmentStorage.resolve("not-an-attachment-id"))
        assertNotNull(EncryptedAttachmentStorage.resolve(id))
    }

    @Test
    fun `delete removes both part and bin`() = withAttachment { id ->
        EncryptedAttachmentStorage.appendChunk(id, 0, "partial".toByteArray(), maxSize = 64)
        assertTrue(EncryptedAttachmentStorage.delete(id))
        assertEquals(0L, EncryptedAttachmentStorage.uploadedBytes(id))
        EncryptedAttachmentStorage.appendChunk(id, 0, "partial".toByteArray(), maxSize = 64)
        EncryptedAttachmentStorage.finalizeResumableUpload(id)
        assertTrue(EncryptedAttachmentStorage.delete(id))
        assertNull(EncryptedAttachmentStorage.resolve(id))
    }
}
