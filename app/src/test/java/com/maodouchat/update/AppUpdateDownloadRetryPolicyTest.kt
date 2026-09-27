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
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("timeout"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Unable to resolve host \"x\": No address associated"))
    }

    /**
     * G340：瞬态 socket 类必须可重试——实测在 AVD 上对官方域名不可达端口得到的就是
     * `Connection reset`（旧分类漏了它 → 更新静默放弃）。同类还包括服务端重启
     * （connection abort）与截断下载（broken pipe / unexpected end of stream）。
     */
    @Test
    fun transientSocketFailuresAreRetryable() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Connection reset"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Software caused connection abort"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Broken pipe"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("unexpected end of stream"))
    }

    /**
     * G340：类型优先——端口探测实测同一失败会给出不同 message（`Connection reset` /
     * `connection closed`），逐个枚举字符串列不全；IOException 一族一律可重试。
     * 非 IO 异常必须回落到文本判据（不能把校验类失败也当成瞬态）。
     */
    @Test
    fun ioExceptionsAreRetryableByType() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.SocketException("Connection reset")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.SocketException("connection closed")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.UnknownHostException("x")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.ConnectException("Connection refused")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun nonIoExceptionsFallBackToTextRules() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(IllegalStateException("apk_sha256_mismatch")))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(IllegalArgumentException("apk_not_official")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(IllegalStateException("http_503")))
    }

    @Test
    fun integrityFailuresAreNotRetryable() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_sha256_mismatch"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_package_signer_or_version_mismatch"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_not_official"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("redirect_host_not_official"))
    }
}
