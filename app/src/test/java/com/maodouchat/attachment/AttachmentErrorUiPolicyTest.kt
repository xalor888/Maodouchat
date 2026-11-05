package com.maodouchat.attachment

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.AttachmentErrorKind
import com.maodouchat.core.crypto.AttachmentCryptoException
import com.maodouchat.core.crypto.AttachmentCryptoFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentErrorUiPolicyTest {

    @Test
    fun classifiesCryptoFailures() {
        assertEquals(
            AttachmentErrorKind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE))
        )
        assertEquals(
            AttachmentErrorKind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE))
        )
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED))
        )
        assertEquals(
            AttachmentErrorKind.CONTENT_MISMATCH,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT)
            )
        )
    }

    @Test
    fun invalidApiResponseMapsToIntegrity() {
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE, serverMessage = "bad")
            )
        )
    }

    @Test
    fun classifiesWorkflowBoundaryFailures() {
        assertEquals(
            AttachmentErrorKind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentTooLargeException())
        )
        assertEquals(
            AttachmentErrorKind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentReferenceInvalidException())
        )
    }

    @Test
    fun unknownFallsBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(IllegalStateException("x"))
        )
    }

    @Test
    fun networkAndTimeoutKindsFallBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "eof")
            )
        )
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.TIMEOUT, serverMessage = "slow")
            )
        )
    }

    @Test
    fun httpStatusErrorsFallBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, statusCode = 500, serverMessage = "boom")
            )
        )
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, statusCode = 404, serverMessage = "nope")
            )
        )
    }

    @Test
    fun invalidResponseAlwaysMapsToIntegrity() {
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(
                    kind = ApiFailureKind.INVALID_RESPONSE,
                    statusCode = 200,
                    serverMessage = "ok"
                )
            )
        )
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE)
            )
        )
    }

    @Test
    fun mapsEveryCryptoFailureDeterministically() {
        val expected = mapOf(
            AttachmentCryptoFailure.TOO_LARGE to AttachmentErrorKind.TOO_LARGE,
            AttachmentCryptoFailure.READ_FAILED to AttachmentErrorKind.FALLBACK,
            AttachmentCryptoFailure.SIZE_MISMATCH to AttachmentErrorKind.FALLBACK,
            AttachmentCryptoFailure.INTEGRITY_FAILED to AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentCryptoFailure.INVALID_REFERENCE to AttachmentErrorKind.INVALID_REFERENCE,
            AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT to AttachmentErrorKind.CONTENT_MISMATCH
        )
        for (failure in AttachmentCryptoFailure.entries) {
            assertEquals(
                expected.getValue(failure),
                AttachmentErrorUiPolicy.classify(AttachmentCryptoException(failure))
            )
        }
    }

    @Test
    fun cryptoCauseDoesNotChangeClassification() {
        val wrapped = AttachmentCryptoException(
            AttachmentCryptoFailure.TOO_LARGE,
            IOException("disk")
        )
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorUiPolicy.classify(wrapped))
    }
    @Test
    fun invalidResponseKindBeatsHttp500Status() {
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE, statusCode = 500, serverMessage = "boom")
            )
        )
    }

    @Test
    fun httpKindWithoutStatusFallsBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, serverMessage = "boom")
            )
        )
    }

    @Test
    fun unexpectedKindFallsBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.UNEXPECTED, serverMessage = "weird")
            )
        )
    }

    @Test
    fun cryptoFailureWrappedInPlainExceptionFallsBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                RuntimeException("wrap", AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE))
            )
        )
    }

    @Test
    fun repeatedClassificationIsStable() {
        val tooLarge = AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorUiPolicy.classify(tooLarge))
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorUiPolicy.classify(tooLarge))
        val unknown = IllegalStateException("x")
        assertEquals(AttachmentErrorKind.FALLBACK, AttachmentErrorUiPolicy.classify(unknown))
        assertEquals(AttachmentErrorKind.FALLBACK, AttachmentErrorUiPolicy.classify(unknown))
    }


    @Test
    fun allNonInvalidResponseApiKindsFallBack() {
        for (kind in ApiFailureKind.entries) {
            if (kind == ApiFailureKind.INVALID_RESPONSE) continue
            assertEquals(
                AttachmentErrorKind.FALLBACK,
                AttachmentErrorUiPolicy.classify(
                    ApiException(kind = kind, statusCode = 400, serverMessage = "x")
                )
            )
        }
    }

    @Test
    fun readFailedAndSizeMismatchWithCausesStillFallBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.READ_FAILED, IOException("disk"))
            )
        )
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.SIZE_MISMATCH, IOException("disk"))
            )
        )
    }

    @Test
    fun integrityAndContentMismatchIgnoreCause() {
        assertEquals(
            AttachmentErrorKind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED, IOException("disk"))
            )
        )
        assertEquals(
            AttachmentErrorKind.CONTENT_MISMATCH,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(
                    AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT,
                    IOException("disk")
                )
            )
        )
    }

    @Test
    fun errorNotExceptionFallsBack() {
        assertEquals(
            AttachmentErrorKind.FALLBACK,
            AttachmentErrorUiPolicy.classify(AssertionError("boom"))
        )
    }

    @Test
    fun workflowExceptionsAreInstanceIndependent() {
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorUiPolicy.classify(AttachmentTooLargeException()))
        assertEquals(AttachmentErrorKind.TOO_LARGE, AttachmentErrorUiPolicy.classify(AttachmentTooLargeException()))
        assertEquals(
            AttachmentErrorKind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentReferenceInvalidException())
        )
        assertEquals(
            AttachmentErrorKind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentReferenceInvalidException())
        )
    }
}
