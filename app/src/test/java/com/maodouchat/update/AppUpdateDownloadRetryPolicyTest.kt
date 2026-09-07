package com.maodouchat.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateDownloadRetryPolicyTest {

    @Test
    fun networkAndHttpAreRetryable() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_503"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("empty_body"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("apk_too_small"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("failed to connect to host"))
    }

    @Test
    fun integrityFailuresAreNotRetryable() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_sha256_mismatch"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_package_signer_or_version_mismatch"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_not_official"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("redirect_host_not_official"))
    }
}
