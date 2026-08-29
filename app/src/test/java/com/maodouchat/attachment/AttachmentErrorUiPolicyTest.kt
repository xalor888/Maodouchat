package com.maodouchat.attachment

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentErrorUiPolicyTest {

    @Test
    fun classifiesCryptoFailures() {
        assertEquals(
            AttachmentErrorUiPolicy.Kind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE))
        )
        assertEquals(
            AttachmentErrorUiPolicy.Kind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE))
        )
        assertEquals(
            AttachmentErrorUiPolicy.Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED))
        )
        assertEquals(
            AttachmentErrorUiPolicy.Kind.CONTENT_MISMATCH,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT)
            )
        )
    }

    @Test
    fun invalidApiResponseMapsToIntegrity() {
        assertEquals(
            AttachmentErrorUiPolicy.Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE, serverMessage = "bad")
            )
        )
    }

    @Test
    fun classifiesWorkflowBoundaryFailures() {
        assertEquals(
            AttachmentErrorUiPolicy.Kind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentTooLargeException())
        )
        assertEquals(
            AttachmentErrorUiPolicy.Kind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentReferenceInvalidException())
        )
    }

    @Test
    fun unknownFallsBack() {
        assertEquals(
            AttachmentErrorUiPolicy.Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(IllegalStateException("x"))
        )
    }
}
