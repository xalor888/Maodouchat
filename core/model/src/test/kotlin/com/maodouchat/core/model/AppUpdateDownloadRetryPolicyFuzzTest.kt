package com.maodouchat.core.model

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AppUpdateDownloadRetryPolicy]
 * (checklist line 931, 34th block). Test-only, zero production changes.
 *
 * The string oracle re-derives the classification from the keyword list in
 * the policy's doc comment: `http_` prefix (case-sensitive) or the exact
 * codes `empty_body` / `apk_too_small`, or a case-insensitive substring
 * match on one of the seven transient phrases. The random input generator
 * stitches fragments together so keywords appear in random cases, embedded
 * in noise, split across fragment boundaries, or as near-misses
 * (`connectionreset`, `time out`, `Http_404`). A Python mini-model
 * differentially checks the oracle on 5000 random strings plus every
 * fragment alone and embedded (0 diffs).
 */
class AppUpdateDownloadRetryPolicyFuzzTest {

    private val fragments = listOf(
        "timeout", "TIMEOUT", "Timeout", "tImEoUt",
        "Unable to resolve", "UNABLE TO RESOLVE host",
        "failed to connect", "FAILED TO CONNECT",
        "Connection reset", "CONNECTION RESET",
        "connection abort", "Software caused connection abort",
        "Broken pipe", "BROKEN PIPE",
        "unexpected end of stream", "Unexpected End Of Stream",
        "http_404", "http_500", "http_", "Http_404", "HTTP_404",
        "empty_body", "apk_too_small", "EMPTY_BODY",
        "sha mismatch", "signature invalid", "version mismatch",
        "not official url", "ssl error", "disk full", "cancelled",
        "ok", "", " ", "connectionreset", "time out", "timeouts", "xhttp_404"
    )

    private val keywords = listOf(
        "timeout", "Unable to resolve", "failed to connect",
        "connection reset", "connection abort", "broken pipe",
        "unexpected end of stream"
    )

    private fun oracleIsRetryable(code: String): Boolean {
        if (code.startsWith("http_")) return true
        if (code == "empty_body" || code == "apk_too_small") return true
        val lower = code.lowercase()
        return keywords.any { lower.contains(it.lowercase()) }
    }

    private fun randomCode(r: Random): String {
        val separators = listOf(" ", ".", ":", "/", "-", "_", "x")
        return buildString {
            repeat(r.nextInt(6)) {
                append(fragments[r.nextInt(fragments.size)])
                if (r.nextBoolean()) append(separators[r.nextInt(separators.size)])
            }
        }
    }

    @Test
    fun isRetryableStringMatchesOracle() {
        val r = Random(3412)
        repeat(300) {
            val code = randomCode(r)
            assertEquals(oracleIsRetryable(code), AppUpdateDownloadRetryPolicy.isRetryable(code))
        }
    }

    @Test
    fun keywordFragmentsAreClassified() {
        for (fragment in fragments) {
            assertEquals(
                oracleIsRetryable(fragment),
                AppUpdateDownloadRetryPolicy.isRetryable(fragment),
                "fragment <$fragment>"
            )
            val embedded = "xx $fragment yy"
            assertEquals(
                oracleIsRetryable(embedded),
                AppUpdateDownloadRetryPolicy.isRetryable(embedded),
                "embedded <$fragment>"
            )
        }
        // Near-misses that must stay non-retryable.
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("connectionreset"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("time out"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("Http_404"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("EMPTY_BODY"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("sha mismatch"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(""))
        // Exact codes and prefix are retryable.
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_404"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("empty_body"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("apk_too_small"))
    }

    @Test
    fun isRetryableThrowablePrefersTypeOverText() {
        val r = Random(3413)
        repeat(100) {
            val code = randomCode(r)
            // Any IOException is retryable whatever its message says, even null.
            assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(IOException(code)))
            assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(IOException()))
            assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(SocketTimeoutException(code)))
            assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(UnknownHostException(code)))
            // Non-IO exceptions fall back to the text rule on the message.
            assertEquals(
                oracleIsRetryable(code),
                AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException(code))
            )
            assertEquals(
                oracleIsRetryable(code),
                AppUpdateDownloadRetryPolicy.isRetryable(IllegalStateException(code))
            )
        }
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException()))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("sha mismatch")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("Connection reset by peer")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(IOException("sha mismatch")))
    }
}
