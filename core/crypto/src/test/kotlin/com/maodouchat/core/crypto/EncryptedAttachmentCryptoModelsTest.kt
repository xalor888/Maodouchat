package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EncryptedAttachmentCryptoModelsTest {

    @Test
    fun `encrypted file holds its hashes and sizes`() {
        val f = EncryptedFile(File("a.bin"), "k", "iv", "c".repeat(64), "p".repeat(64), 10L, 9L)
        assertEquals(10L, f.cipherSize)
        assertEquals(9L, f.plainSize)
        assertTrue(f.copy(cipherSize = 11L).cipherSize == 11L)
    }
}
