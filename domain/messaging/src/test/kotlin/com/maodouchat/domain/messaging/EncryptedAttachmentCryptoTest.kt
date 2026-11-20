package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir
import com.maodouchat.core.crypto.EncryptedFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

class EncryptedAttachmentCryptoTest {
    @TempDir
    lateinit var tempDir: File

    private fun newFile(name: String) = File(tempDir, name).apply { createNewFile() }

    private fun newFolder(name: String) = File(tempDir, name).apply { mkdirs() }

    @Test
    fun `encrypt decrypt round trip preserves bytes`() {
        val source = newFile("source.bin").apply {
            writeBytes(ByteArray(64 * 1024) { index -> (index % 251).toByte() })
        }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("uploads"))
        val target = newFile("restored.bin")

        EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())

        assertContentEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `tampered ciphertext fails integrity verification`() {
        val source = newFile("tamper-source.bin").apply { writeText("confidential attachment body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("tamper-uploads"))
        RandomAccessFile(encrypted.file, "rw").use { file ->
            val firstByte = file.read()
            file.seek(0)
            file.write(firstByte.xor(0x01))
        }

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                newFile("tampered-output.bin"),
                encrypted.reference()
            )
        }
    }

    @Test
    fun `wrong key and plaintext hash fail verification`() {
        val source = newFile("wrong-key-source.bin").apply { writeText("another private attachment") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("wrong-key-uploads"))
        val reference = encrypted.reference()
        val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                newFile("wrong-key-output.bin"),
                reference.copy(keyBase64 = wrongKey)
            )
        }
        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                newFile("wrong-hash-output.bin"),
                reference.copy(plainSha256 = "0".repeat(64))
            )
        }
    }

    @Test
    fun `empty source fails with size mismatch`() {
        val source = newFile("empty.bin") // 0 字节：copied <= 0 直接拒
        assertCryptoFailure(AttachmentCryptoFailure.SIZE_MISMATCH) {
            EncryptedAttachmentCrypto.encryptFile(source, newFolder("empty-uploads"))
        }
    }

    @Test
    fun `truncated ciphertext fails integrity and deletes target`() {
        val source = newFile("trunc-source.bin").apply { writeText("truncation test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("trunc-uploads"))
        RandomAccessFile(encrypted.file, "rw").use { it.setLength(encrypted.cipherSize / 2) }
        val target = newFile("trunc-output.bin")

        assertCryptoFailure(AttachmentCryptoFailure.INTEGRITY_FAILED) {
            EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())
        }
        assertFalse(target.exists(), "完整性失败后目标文件必须被删除")
    }

    @Test
    fun `malformed base64 key fails with invalid reference`() {
        val source = newFile("b64-source.bin").apply { writeText("base64 test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("b64-uploads"))

        assertCryptoFailure(AttachmentCryptoFailure.INVALID_REFERENCE) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                newFile("b64-output.bin"),
                encrypted.reference().copy(keyBase64 = "!!!not-base64!!!")
            )
        }
    }

    @Test
    fun `wrong iv length fails with invalid reference`() {
        val source = newFile("iv-source.bin").apply { writeText("iv length test body") }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("iv-uploads"))
        val shortIv = Base64.getEncoder().encodeToString(ByteArray(8) { 3 }) // GCM 要求 12 字节

        assertCryptoFailure(AttachmentCryptoFailure.INVALID_REFERENCE) {
            EncryptedAttachmentCrypto.decrypt(
                encrypted.file,
                newFile("iv-output.bin"),
                encrypted.reference().copy(ivBase64 = shortIv)
            )
        }
    }

    @Test
    fun `cached plaintext validity tracks content and size`() {
        val body = "cache validity body"
        val source = newFile("cache-source.bin").apply { writeText(body) }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(source, newFolder("cache-uploads"))
        val target = newFile("cache-plain.bin")
        EncryptedAttachmentCrypto.decrypt(encrypted.file, target, encrypted.reference())
        val reference = encrypted.reference()

        assertTrue(EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference), "刚解密出的明文应判有效")
        target.appendText("x") // 长度变化
        assertFalse(EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference), "长度变化后应判无效")
        target.writeText("X".repeat(body.length)) // 同长度但内容被改
        assertFalse(EncryptedAttachmentCrypto.isValidCachedPlaintext(target, reference), "内容被改后应判无效")
        assertFalse(
            EncryptedAttachmentCrypto.isValidCachedPlaintext(File(tempDir, "missing.bin"), reference),
            "不存在的文件应判无效"
        )
    }

    @Test
    fun `same thread encrypt decrypt twice stays clean`() {
        val firstSource = newFile("repeat-first.bin").apply { writeText("first payload") }
        val firstEncrypted = EncryptedAttachmentCrypto.encryptFile(firstSource, newFolder("repeat-first-uploads"))
        val firstTarget = newFile("repeat-first-restored.bin")
        EncryptedAttachmentCrypto.decrypt(firstEncrypted.file, firstTarget, firstEncrypted.reference())
        assertContentEquals(firstSource.readBytes(), firstTarget.readBytes())

        // 同一线程第二次加解密复用 ThreadLocal 的 digest/cipher，残留状态会让完整性校验失败。
        val secondSource = newFile("repeat-second.bin").apply { writeText("second payload, different bytes") }
        val secondEncrypted = EncryptedAttachmentCrypto.encryptFile(secondSource, newFolder("repeat-second-uploads"))
        val secondTarget = newFile("repeat-second-restored.bin")
        EncryptedAttachmentCrypto.decrypt(secondEncrypted.file, secondTarget, secondEncrypted.reference())
        assertContentEquals(secondSource.readBytes(), secondTarget.readBytes())
        assertTrue(EncryptedAttachmentCrypto.isValidCachedPlaintext(secondTarget, secondEncrypted.reference()))
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
