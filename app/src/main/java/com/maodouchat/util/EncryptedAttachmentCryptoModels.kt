package com.maodouchat.util

import java.io.File

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

data class EncryptedFile(
    val file: File,
    val keyBase64: String,
    val ivBase64: String,
    val cipherSha256: String,
    val plainSha256: String,
    val cipherSize: Long,
    val plainSize: Long
)
