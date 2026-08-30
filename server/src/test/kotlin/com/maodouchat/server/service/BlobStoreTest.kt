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

class BlobStoreTest {

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
            BlobStore.delete(id)
        }
    }

    @Test
    fun `rejects invalid attachment ids`() {
        assertFailsWith<IllegalArgumentException> {
            BlobStore.createTempFile("att_short")
        }
        assertFailsWith<IllegalArgumentException> {
            BlobStore.createTempFile("../att_xxxxxxxxxxxxxxxxxxxx")
        }
        assertFailsWith<IllegalArgumentException> {
            BlobStore.createTempFile("att_xxxxxxxxxxxxxxxxxxxx/../x")
        }
        assertNull(BlobStore.resolve("att_short"))
        assertNull(BlobStore.sha256("../not-an-id"))
        assertFalse(BlobStore.delete("att_too_short"))
    }

    @Test
    fun `append chunk replay and content mismatch`() = withAttachment { id ->
        val first = "ciphertext-chunk-one".toByteArray()
        val second = "chunk-two".toByteArray()
        val accepted = BlobStore.appendChunk(id, 0, first, maxSize = 1024)
        assertEquals(
            BlobStore.AppendResult.Accepted(first.size.toLong(), replayed = false),
            accepted
        )
        val replay = BlobStore.appendChunk(id, 0, first, maxSize = 1024)
        assertEquals(
            BlobStore.AppendResult.Accepted(first.size.toLong(), replayed = true),
            replay
        )
        val mismatch = BlobStore.appendChunk(id, 0, "different-bytes!!!!".toByteArray(), maxSize = 1024)
        assertEquals(BlobStore.AppendResult.ContentMismatch, mismatch)

        val next = BlobStore.appendChunk(id, first.size.toLong(), second, maxSize = 1024)
        assertEquals(
            BlobStore.AppendResult.Accepted((first.size + second.size).toLong(), replayed = false),
            next
        )
        val gap = BlobStore.appendChunk(id, 999L, second, maxSize = 1024)
        assertTrue(gap is BlobStore.AppendResult.OffsetMismatch)
        assertEquals((first.size + second.size).toLong(), BlobStore.uploadedBytes(id))
    }

    @Test
    fun `finalize is idempotent when bin exists and part is gone`() = withAttachment { id ->
        val payload = "opaque-ciphertext-bytes".toByteArray()
        BlobStore.appendChunk(id, 0, payload, maxSize = 1024)
        val first = BlobStore.finalizeResumableUpload(id)
        assertNotNull(first)
        assertTrue(first.isFile)
        assertEquals(payload.size.toLong(), first.length())
        assertEquals(sha256Hex(payload), BlobStore.sha256(id))

        val again = BlobStore.finalizeResumableUpload(id)
        assertNotNull(again)
        assertEquals(first.canonicalFile, again.canonicalFile)

        val oneShot = BlobStore.finalizeUpload(id, BlobStore.createTempFile(id))
        assertEquals(first.canonicalFile, oneShot.canonicalFile)
        assertNull(BlobStore.resolve("not-an-attachment-id"))
        assertNotNull(BlobStore.resolve(id))
    }

    @Test
    fun `delete removes both part and bin`() = withAttachment { id ->
        BlobStore.appendChunk(id, 0, "partial".toByteArray(), maxSize = 64)
        assertTrue(BlobStore.delete(id))
        assertEquals(0L, BlobStore.uploadedBytes(id))
        BlobStore.appendChunk(id, 0, "partial".toByteArray(), maxSize = 64)
        BlobStore.finalizeResumableUpload(id)
        assertTrue(BlobStore.delete(id))
        assertNull(BlobStore.resolve(id))
    }
}
