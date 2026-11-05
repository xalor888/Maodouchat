package com.maodouchat.attachment

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.AttachmentErrorKind
import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure

/**
 * Map transfer failures to UI string kinds (no Android resources here).
 */
object AttachmentErrorUiPolicy {

    fun classify(error: Throwable): AttachmentErrorKind = when {
        error is AttachmentTooLargeException -> AttachmentErrorKind.TOO_LARGE
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.TOO_LARGE ->
            AttachmentErrorKind.TOO_LARGE
        error is AttachmentReferenceInvalidException ||
            error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.INVALID_REFERENCE ->
            AttachmentErrorKind.INVALID_REFERENCE
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.INTEGRITY_FAILED ->
            AttachmentErrorKind.INTEGRITY_FAILED
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT ->
            AttachmentErrorKind.CONTENT_MISMATCH
        error is ApiException && error.kind == ApiFailureKind.INVALID_RESPONSE ->
            AttachmentErrorKind.INTEGRITY_FAILED
        else -> AttachmentErrorKind.FALLBACK
    }
}
