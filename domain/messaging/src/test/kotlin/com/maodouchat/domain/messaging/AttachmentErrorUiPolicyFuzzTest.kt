package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// classify restated by hand: the exception type decides the kind --
// a too-large transfer, a crypto failure of TOO_LARGE, an invalid
// reference (own type or crypto failure), an integrity failure
// (crypto or an INVALID_RESPONSE api failure), an unsupported media
// content mismatch; anything else falls back.
internal fun errorKindOracle172(error: Throwable): AttachmentErrorKind = when {
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

// Production classify returns the UI error kind.
internal fun errorKindProduction172(error: Throwable): AttachmentErrorKind =
    AttachmentErrorUiPolicy.classify(error)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonError172(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun escJsonOrNullError172(s: String?): String =
    if (s == null) "null" else escJsonError172(s)

// One exception per tag; the failure / apiKind fields only matter
// for the crypto / api tags and are ignored otherwise.
internal fun buildError172(
    tag: String,
    failure: AttachmentCryptoFailure?,
    apiKind: ApiFailureKind?
): Throwable = when (tag) {
    "tooLarge" -> AttachmentTooLargeException()
    "crypto" -> AttachmentCryptoException(failure ?: AttachmentCryptoFailure.READ_FAILED)
    "refInvalid" -> AttachmentReferenceInvalidException()
    "api" -> ApiException(apiKind ?: ApiFailureKind.HTTP)
    "runtime" -> RuntimeException("boom")
    "illegalState" -> IllegalStateException("x")
    else -> IOException("io")
}

internal data class ErrorCase172(
    val tag: String,
    val failure: AttachmentCryptoFailure?,
    val apiKind: ApiFailureKind?
)

internal val ERROR_TAG_POOL172: List<String> = listOf(
    "tooLarge", "crypto", "refInvalid", "api", "runtime", "illegalState", "io"
)

internal val ERROR_FAILURE_POOL172: List<AttachmentCryptoFailure> = listOf(
    AttachmentCryptoFailure.TOO_LARGE,
    AttachmentCryptoFailure.READ_FAILED,
    AttachmentCryptoFailure.SIZE_MISMATCH,
    AttachmentCryptoFailure.INTEGRITY_FAILED,
    AttachmentCryptoFailure.INVALID_REFERENCE,
    AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT
)

internal val ERROR_API_KIND_POOL172: List<ApiFailureKind> = listOf(
    ApiFailureKind.HTTP,
    ApiFailureKind.TIMEOUT,
    ApiFailureKind.NETWORK,
    ApiFailureKind.INVALID_RESPONSE,
    ApiFailureKind.UNEXPECTED
)

internal fun randomErrorCase172(rnd: Random): ErrorCase172 {
    val tag = ERROR_TAG_POOL172[rnd.nextInt(ERROR_TAG_POOL172.size)]
    return ErrorCase172(
        tag = tag,
        failure = if (tag == "crypto") ERROR_FAILURE_POOL172[rnd.nextInt(ERROR_FAILURE_POOL172.size)] else null,
        apiKind = if (tag == "api") ERROR_API_KIND_POOL172[rnd.nextInt(ERROR_API_KIND_POOL172.size)] else null
    )
}

internal fun errorWireJson172(c: ErrorCase172): String =
    "{\"tag\":${escJsonError172(c.tag)}," +
        "\"failure\":${escJsonOrNullError172(c.failure?.name)}," +
        "\"apiKind\":${escJsonOrNullError172(c.apiKind?.name)}}"

class AttachmentErrorUiPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            ErrorCase172("tooLarge", null, null) to AttachmentErrorKind.TOO_LARGE,
            ErrorCase172("refInvalid", null, null) to AttachmentErrorKind.INVALID_REFERENCE,
            ErrorCase172("runtime", null, null) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("illegalState", null, null) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("io", null, null) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("crypto", AttachmentCryptoFailure.TOO_LARGE, null) to AttachmentErrorKind.TOO_LARGE,
            ErrorCase172("crypto", AttachmentCryptoFailure.READ_FAILED, null) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("crypto", AttachmentCryptoFailure.SIZE_MISMATCH, null) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("crypto", AttachmentCryptoFailure.INTEGRITY_FAILED, null) to AttachmentErrorKind.INTEGRITY_FAILED,
            ErrorCase172("crypto", AttachmentCryptoFailure.INVALID_REFERENCE, null) to AttachmentErrorKind.INVALID_REFERENCE,
            ErrorCase172("crypto", AttachmentCryptoFailure.UNSUPPORTED_MEDIA_CONTENT, null) to AttachmentErrorKind.CONTENT_MISMATCH,
            ErrorCase172("api", null, ApiFailureKind.HTTP) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("api", null, ApiFailureKind.TIMEOUT) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("api", null, ApiFailureKind.NETWORK) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("api", null, ApiFailureKind.INVALID_RESPONSE) to AttachmentErrorKind.INTEGRITY_FAILED,
            ErrorCase172("api", null, ApiFailureKind.UNEXPECTED) to AttachmentErrorKind.FALLBACK,
            // Fields for the other tag are ignored: a crypto failure
            // riding on the api tag changes nothing, and vice versa.
            ErrorCase172("api", AttachmentCryptoFailure.TOO_LARGE, ApiFailureKind.HTTP) to AttachmentErrorKind.FALLBACK,
            ErrorCase172("tooLarge", AttachmentCryptoFailure.INTEGRITY_FAILED, ApiFailureKind.INVALID_RESPONSE) to AttachmentErrorKind.TOO_LARGE
        )
        for ((c, expected) in cases) {
            val error = buildError172(c.tag, c.failure, c.apiKind)
            assertEquals(expected, errorKindProduction172(error), "classify($c)")
            assertEquals(expected, errorKindOracle172(error), "oracle($c)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(172005)
        repeat(4000) {
            val c = randomErrorCase172(rnd)
            val error = buildError172(c.tag, c.failure, c.apiKind)
            assertEquals(
                errorKindOracle172(error),
                errorKindProduction172(error),
                "classify(random $c)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every tag against every failure and every api kind: the
        // ignored fields must never change the outcome.
        for (tag in ERROR_TAG_POOL172) {
            for (failure in listOf(null) + ERROR_FAILURE_POOL172) {
                for (apiKind in listOf(null) + ERROR_API_KIND_POOL172) {
                    val c = ErrorCase172(tag, failure, apiKind)
                    val error = buildError172(c.tag, c.failure, c.apiKind)
                    assertEquals(
                        errorKindOracle172(error),
                        errorKindProduction172(error),
                        "sweep($c)"
                    )
                }
            }
        }
    }
}
