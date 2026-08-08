package com.maodouchat.attachment

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure

/**
 * Map transfer failures to UI string kinds (no Android resources here).
 */
object AttachmentErrorUiPolicy {

    enum class Kind {
        TOO_LARGE,
        INVALID_REFERENCE,
        INTEGRITY_FAILED,
        CONTENT_MISMATCH,
        FALLBACK
    }

    fun classify(error: Throwable): Kind = when {
        error is AttachmentCryptoException && error.failure == AttachmentCryptoFailure.TOO_LARGE ->
            Kind.TOO_LARGE
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
