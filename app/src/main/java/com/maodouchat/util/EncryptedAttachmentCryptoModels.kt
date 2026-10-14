package com.maodouchat.util

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
