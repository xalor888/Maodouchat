package com.maodouchat.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.Base64

class EncryptedAttachmentCryptoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `encrypt decrypt round trip preserves bytes`() {
        val source = temporaryFolder.newFile("source.bin").apply {
            writeBytes(ByteArray(64 * 1024) { index -> (index % 251).toByte() })
        }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("uploads"))
        val target = temporaryFolder.newFile("restored.bin")

        EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())

        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `tampered ciphertext fails integrity verification`() {
        val source = temporaryFolder.newFile("tamper-source.bin").apply { writeText("confidential attachment body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("tamper-uploads"))
        RandomAccessFile(encrypted.file, "rw").use { file ->
            val firstByte = file.read()
            file.seek(0)
            file.write(firstByte.xor(0x01))
        }

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                temporaryFolder.newFile("tampered-output.bin"),
                encrypted.reference()
            )
        }
    }

    @Test
    fun `wrong key and plaintext hash fail verification`() {
        val source = temporaryFolder.newFile("wrong-key-source.bin").apply { writeText("another private attachment") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("wrong-key-uploads"))
        val reference = encrypted.reference()
        val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                temporaryFolder.newFile("wrong-key-output.bin"),
                reference.copy(keyBase64 = wrongKey)
            )
        }
        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                temporaryFolder.newFile("wrong-hash-output.bin"),
                reference.copy(plainSha256 = "0".repeat(64))
            )
        }
    }

    private fun EncryptedAttachmentCrypto.EncryptedFile.reference() = MediaCache.EncryptedAttachmentReference(
        attachmentId = "att_12345678901234567890",
        keyBase64 = keyBase64,
        ivBase64 = ivBase64,
        cipherSha256 = cipherSha256,
        plainSha256 = plainSha256,
        cipherSize = cipherSize,
        fileName = "document.bin",
        mimeType = "application/octet-stream",
        plainSize = plainSize
    )

    private fun assertCryptoFailure(expected: AttachmentCryptoFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected AttachmentCryptoException")
        } catch (error: AttachmentCryptoException) {
            assertEquals(expected, error.failure)
        }
    }
}
