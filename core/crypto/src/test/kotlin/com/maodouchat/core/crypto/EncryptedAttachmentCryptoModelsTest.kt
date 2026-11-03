package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EncryptedAttachmentCryptoModelsTest {

    @Test
    fun `exception message is the failure name`() {
        val e = AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
        assertEquals("TOO_LARGE", e.message)
        assertEquals(AttachmentCryptoFailure.TOO_LARGE, e.failure)
        assertNull(e.cause)
    }

    @Test
    fun `exception carries its cause`() {
        val cause = IllegalStateException("disk")
        val e = AttachmentCryptoException(AttachmentCryptoFailure.READ_FAILED, cause)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `encrypted file holds its hashes and sizes`() {
        val f = EncryptedFile(File("a.bin"), "k", "iv", "c".repeat(64), "p".repeat(64), 10L, 9L)
        assertEquals(10L, f.cipherSize)
        assertEquals(9L, f.plainSize)
        assertTrue(f.copy(cipherSize = 11L).cipherSize == 11L)
    }

    @Test
    fun `failure enum covers all six outcomes`() {
        assertEquals(6, AttachmentCryptoFailure.values().size)
    }
}
