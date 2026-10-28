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

    @Test
    fun httpPrefixCoversAnyStatusCode() {
        // 判据只看 http_ 前缀，不校验状态码本身。
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_404"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_500"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_200"))
    }

    @Test
    fun substringMatchesAreCaseInsensitive() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("TIMEOUT"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Connection Reset"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("BROKEN PIPE"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Failed To Connect"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("UNABLE TO RESOLVE host"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Unexpected End Of Stream"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("Software Caused Connection Abort"))
    }

    @Test
    fun nearMissCodesAreNotRetryable() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("http"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("HTTP_503"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("empty_bod"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_too_small_extra"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("connection"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("reset"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(""))
    }

    @Test
    fun ioExceptionsAreRetryableRegardlessOfMessage() {
        // 类型优先：哪怕 message 与已知规则全不沾边，IOException 一族照样重试。
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.io.IOException("totally unrelated")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.io.EOFException("eof")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.SocketException("closed")))
    }

    @Test
    fun nonIoExceptionsWithRetryableTextOrEmptyMessage() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("timeout while fetching")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("Connection reset by peer")))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("empty_body")))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException()))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("")))
    }
    @Test
    fun httpPrefixIsCaseSensitiveAndPositionSensitive() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("xhttp_503"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(" http_503"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http__503"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("http_"))
    }

    @Test
    fun emptyBodyAndTooSmallAreExactCodesOnly() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("empty_body "))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("EMPTY_BODY"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("empty_bodyx"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("apk_too_small "))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("APK_TOO_SMALL"))
    }

    @Test
    fun ioExceptionWithNullMessageIsRetryable() {
        // 类型优先：message 为空也不影响，IOException 一族照样重试。
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.io.IOException(null as String?)))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(java.net.SocketException(null as String?)))
    }

    @Test
    fun timeoutSubstringNeedsTheFullWord() {
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("read timeout")))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("operation timed out")))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable(RuntimeException("time out")))
    }

    @Test
    fun connectionPhrasesRequireFullPhraseInOrder() {
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("connection failed"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("pipe broken"))
        assertFalse(AppUpdateDownloadRetryPolicy.isRetryable("end of stream"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("unexpected end of stream while reading"))
        assertTrue(AppUpdateDownloadRetryPolicy.isRetryable("connection aborted by peer"))
    }

}
