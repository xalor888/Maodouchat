package com.maodouchat.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class AttachmentCryptoFailure {
    TOO_LARGE,
    READ_FAILED,
    SIZE_MISMATCH,
    INTEGRITY_FAILED,
    INVALID_REFERENCE,
    UNSUPPORTED_MEDIA_CONTENT
}

class AttachmentCryptoException(
    val failure: AttachmentCryptoFailure,
    cause: Throwable? = null
) : Exception(failure.name, cause)

object EncryptedAttachmentCrypto {
    data class EncryptedFile(
        val file: File,
        val keyBase64: String,
        val ivBase64: String,
        val cipherSha256: String,
        val plainSha256: String,
        val cipherSize: Long,
        val plainSize: Long
    )

    fun encrypt(
        context: Context,
        uri: Uri,
        expectedPlainSize: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): EncryptedFile {
        val directory = File(context.cacheDir, "attachment-uploads").apply { mkdirs() }
        val target = File.createTempFile("encrypted_", ".bin", directory)
        return encryptToTarget(target, expectedPlainSize, { context.contentResolver.openInputStream(uri) }, onProgress)
    }

    internal fun encryptFile(
        source: File,
        targetDirectory: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): EncryptedFile {
        targetDirectory.mkdirs()
        val target = File.createTempFile("encrypted_", ".bin", targetDirectory)
        return encryptToTarget(target, source.length(), { source.inputStream() }, onProgress)
    }

    private fun encryptToTarget(
        target: File,
        expectedPlainSize: Long,
        openInput: () -> InputStream?,
        onProgress: (Long, Long) -> Unit
    ): EncryptedFile {
        if (expectedPlainSize > MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
            throw AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
        }
        if (expectedPlainSize < 0L) {
            throw AttachmentCryptoException(AttachmentCryptoFailure.SIZE_MISMATCH)
        }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        val plainDigest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }
            openInput()?.use { input ->
                FileOutputStream(target).use { fileOutput ->
                    DigestOutputStream(fileOutput, digest).use { digestOutput ->
                        CipherOutputStream(digestOutput, cipher).use { encryptedOutput ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                copied += read
                                if (copied > MediaCache.MAX_ATTACHMENT_PLAIN_BYTES) {
                                    throw AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
                                }
                                plainDigest.update(buffer, 0, read)
                                encryptedOutput.write(buffer, 0, read)
                                onProgress(copied, expectedPlainSize)
                            }
                        }
                    }
                }
            } ?: throw AttachmentCryptoException(AttachmentCryptoFailure.READ_FAILED)
            if (copied <= 0L || (expectedPlainSize != 0L && copied != expectedPlainSize)) {
                throw AttachmentCryptoException(AttachmentCryptoFailure.SIZE_MISMATCH)
            }
            val cipherSize = target.length()
            if (cipherSize !in 17L..MediaCache.MAX_ATTACHMENT_CIPHER_BYTES) {
                throw AttachmentCryptoException(AttachmentCryptoFailure.SIZE_MISMATCH)
            }
            return EncryptedFile(
                file = target,
                keyBase64 = Base64.getEncoder().encodeToString(key),
                ivBase64 = Base64.getEncoder().encodeToString(iv),
                cipherSha256 = digest.digest().toHex(),
                plainSha256 = plainDigest.digest().toHex(),
                cipherSize = cipherSize,
                plainSize = copied
            )
        } catch (error: Throwable) {
            target.delete()
            if (error is CancellationException) throw error
            throw if (error is AttachmentCryptoException) error
            else AttachmentCryptoException(AttachmentCryptoFailure.READ_FAILED, error)
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    fun decrypt(
        encryptedFile: File,
        targetFile: File,
        reference: MediaCache.EncryptedAttachmentReference,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        if (encryptedFile.length() != reference.cipherSize) {
            targetFile.delete()
            throw AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED)
        }
        val key = runCatching { Base64.getDecoder().decode(reference.keyBase64) }
            .getOrElse {
                targetFile.delete()
                throw AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE, it)
            }
        val iv = runCatching { Base64.getDecoder().decode(reference.ivBase64) }
            .getOrElse {
                key.fill(0)
                targetFile.delete()
                throw AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE, it)
            }
        if (key.size != 32 || iv.size != 12) {
            key.fill(0)
            iv.fill(0)
            targetFile.delete()
            throw AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE)
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val plainDigest = MessageDigest.getInstance("SHA-256")
            var cipherRead = 0L
            FileInputStream(encryptedFile).use { fileInput ->
                DigestInputStream(fileInput, digest).use { digestInput ->
                    CipherInputStream(digestInput, cipher).use { decryptedInput ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = decryptedInput.read(buffer)
                                if (read < 0) break
                                plainDigest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                cipherRead = encryptedFile.length() - fileInput.available()
                                onProgress(cipherRead, reference.cipherSize)
                            }
                        }
                    }
                }
            }
            if (
                digest.digest().toHex() != reference.cipherSha256 ||
                targetFile.length() != reference.plainSize ||
                plainDigest.digest().toHex() != reference.plainSha256
            ) {
                throw AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED)
            }
        } catch (error: Throwable) {
            targetFile.delete()
            if (error is CancellationException) throw error
            throw if (error is AttachmentCryptoException) error
            else AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED, error)
        } finally {
            key.fill(0)
            iv.fill(0)
        }
    }

    fun isValidCachedPlaintext(file: File, reference: MediaCache.EncryptedAttachmentReference): Boolean {
        if (!file.isFile || file.length() != reference.plainSize) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex() == reference.plainSha256
        }.getOrDefault(false)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
