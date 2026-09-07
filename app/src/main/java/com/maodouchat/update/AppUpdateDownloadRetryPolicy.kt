package com.maodouchat.update

/** Pure retry classification for [AppUpdateDownloadWorker]. */
object AppUpdateDownloadRetryPolicy {
    fun isRetryable(code: String): Boolean =
        code.startsWith("http_") ||
            code == "empty_body" ||
            code == "apk_too_small" ||
            code.contains("timeout", ignoreCase = true) ||
            code.contains("Unable to resolve", ignoreCase = true) ||
            code.contains("failed to connect", ignoreCase = true)
}
