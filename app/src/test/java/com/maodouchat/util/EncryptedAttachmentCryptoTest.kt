package com.maodouchat.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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

    @Test
    fun `empty source fails with size mismatch`() {
        val source = temporaryFolder.newFile("empty.bin") // 0 字节：copied <= 0 直接拒
        assertCryptoFailure(AttachmentCryptoFailure.SIZE_MISMATCH) {
            EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("empty-uploads"))
        }
    }

    @Test
    fun `truncated ciphertext fails integrity and deletes target`() {
        val source = temporaryFolder.newFile("trunc-source.bin").apply { writeText("truncation test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("trunc-uploads"))
        RandomAccessFile(encrypted.file, "rw").use { it.setLength(encrypted.cipherSize / 2) }
        val target = temporaryFolder.newFile("trunc-output.bin")

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())
        }
        assertFalse("完整性失败后目标文件必须被删除", target.exists())
    }

    @Test
    fun `malformed base64 key fails with invalid reference`() {
        val source = temporaryFolder.newFile("b64-source.bin").apply { writeText("base64 test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("b64-uploads"))

        assertCryptoFailure(AttachmentCryptoFailure.INVALID_REFERENCE) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                temporaryFolder.newFile("b64-output.bin"),
                encrypted.reference().copy(keyBase64 = "!!!not-base64!!!")
            )
        }
    }

    @Test
    fun `wrong iv length fails with invalid reference`() {
        val source = temporaryFolder.newFile("iv-source.bin").apply { writeText("iv length test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("iv-uploads"))
        val shortIv = Base64.getEncoder().encodeToString(ByteArray(8) { 3 }) // GCM 要求 12 字节

        assertCryptoFailure(AttachmentCryptoFailure.INVALID_REFERENCE) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                temporaryFolder.newFile("iv-output.bin"),
                encrypted.reference().copy(ivBase64 = shortIv)
            )
        }
    }

    @Test
    fun `cached plaintext validity tracks content and size`() {
        val body = "cache validity body"
        val source = temporaryFolder.newFile("cache-source.bin").apply { writeText(body) }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, temporaryFolder.newFolder("cache-uploads"))
        val target = temporaryFolder.newFile("cache-plain.bin")
        EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())
        val reference = encrypted.reference()

        assertTrue("刚解密出的明文应判有效", EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference))
        target.appendText("x") // 长度变化
        assertFalse("长度变化后应判无效", EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference))
        target.writeText("X".repeat(body.length)) // 同长度但内容被改
        assertFalse("内容被改后应判无效", EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference))
        assertFalse(
            "不存在的文件应判无效",
            EncryptedAttachmentCrypto.isValidCachedPlaintext(File(temporaryFolder.root, "missing.bin"), reference)
        )
    }

    private fun EncryptedFile.reference() = EncryptedAttachmentReference(
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
