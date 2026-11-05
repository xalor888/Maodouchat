package com.maodouchat.attachment

import com.maodouchat.network.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure

/**
 * Map transfer failures to UI string kinds (no Android resources here).
 */
object AttachmentErrorUiPolicy {

    fun classify(error: Throwable): Kind = when {
        error is AttachmentTooLargeException -> Kind.TOO_LARGE
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.TOO_LARGE ->
            Kind.TOO_LARGE
        error is AttachmentReferenceInvalidException ||
            error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.INVALID_REFERENCE ->
            Kind.INVALID_REFERENCE
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.INTEGRITY_FAILED ->
            Kind.INTEGRITY_FAILED
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT ->
            Kind.CONTENT_MISMATCH
        error is ApiException && error.kind == ApiFailureKind.INVALID_RESPONSE ->
            Kind.INTEGRITY_FAILED
        else -> Kind.FALLBACK
    }
}
