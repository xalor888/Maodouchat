package com.maodouchat.attachment

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentErrorUiPolicyTest {

    @Test
    fun classifiesCryptoFailures() {
        assertEquals(
            Kind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE))
        )
        assertEquals(
            Kind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE))
        )
        assertEquals(
            Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(AttachmentCryptoException(AttachmentCryptoFailure.INTEGRITY_FAILED))
        )
        assertEquals(
            Kind.CONTENT_MISMATCH,
            AttachmentErrorUiPolicy.classify(
                AttachmentCryptoException(AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT)
            )
        )
    }

    @Test
    fun invalidApiResponseMapsToIntegrity() {
        assertEquals(
            Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE, serverMessage = "bad")
            )
        )
    }

    @Test
    fun classifiesWorkflowBoundaryFailures() {
        assertEquals(
            Kind.TOO_LARGE,
            AttachmentErrorUiPolicy.classify(AttachmentTooLargeException())
        )
        assertEquals(
            Kind.INVALID_REFERENCE,
            AttachmentErrorUiPolicy.classify(AttachmentReferenceInvalidException())
        )
    }

    @Test
    fun unknownFallsBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(IllegalStateException("x"))
        )
    }

    @Test
    fun networkAndTimeoutKindsFallBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.NETWORK, serverMessage = "eof")
            )
        )
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.TIMEOUT, serverMessage = "slow")
            )
        )
    }

    @Test
    fun httpStatusErrorsFallBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, statusCode = 500, serverMessage = "boom")
            )
        )
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, statusCode = 404, serverMessage = "nope")
            )
        )
    }

    @Test
    fun invalidResponseAlwaysMapsToIntegrity() {
        assertEquals(
            Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(
                    kind = ApiFailureKind.INVALID_RESPONSE,
                    statusCode = 200,
                    serverMessage = "ok"
                )
            )
        )
        assertEquals(
            Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE)
            )
        )
    }

    @Test
    fun mapsEveryCryptoFailureDeterministically() {
        val expected = mapOf(
            AttachmentCryptoFailure.TOO_LARGE to Kind.TOO_LARGE,
            AttachmentCryptoFailure.READ_FAILED to Kind.FALLBACK,
            AttachmentCryptoFailure.SIZE_MISMATCH to Kind.FALLBACK,
            AttachmentCryptoFailure.INTEGRITY_FAILED to Kind.INTEGRITY_FAILED,
            AttachmentCryptoFailure.INVALID_REFERENCE to Kind.INVALID_REFERENCE,
            AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT to Kind.CONTENT_MISMATCH
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
        assertEquals(Kind.TOO_LARGE, AttachmentErrorUiPolicy.classify(wrapped))
    }
    @Test
    fun invalidResponseKindBeatsHttp500Status() {
        assertEquals(
            Kind.INTEGRITY_FAILED,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.INVALID_RESPONSE, statusCode = 500, serverMessage = "boom")
            )
        )
    }

    @Test
    fun httpKindWithoutStatusFallsBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.HTTP, serverMessage = "boom")
            )
        )
    }

    @Test
    fun unexpectedKindFallsBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                ApiException(kind = ApiFailureKind.UNEXPECTED, serverMessage = "weird")
            )
        )
    }

    @Test
    fun cryptoFailureWrappedInPlainExceptionFallsBack() {
        assertEquals(
            Kind.FALLBACK,
            AttachmentErrorUiPolicy.classify(
                RuntimeException("wrap", AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE))
            )
        )
    }

    @Test
    fun repeatedClassificationIsStable() {
        val tooLarge = AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
        assertEquals(Kind.TOO_LARGE, AttachmentErrorUiPolicy.classify(tooLarge))
        assertEquals(Kind.TOO_LARGE, AttachmentErrorUiPolicy.classify(tooLarge))
        val unknown = IllegalStateException("x")
        assertEquals(Kind.FALLBACK, AttachmentErrorUiPolicy.classify(unknown))
        assertEquals(Kind.FALLBACK, AttachmentErrorUiPolicy.classify(unknown))
    }

}
