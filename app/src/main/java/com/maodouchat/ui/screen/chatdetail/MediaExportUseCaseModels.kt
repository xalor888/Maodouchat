package com.maodouchat.ui.screen.chatdetail

/**
 * 媒体导出请求参数。
 */
data class MediaExportRequest(
    val rawUri: String,
    val mimeType: String,
    val displayName: String,
    val isSecretChat: Boolean,
    val chooserTitle: String = "",
)

/**
 * 媒体导出执行结果。
 */
sealed interface MediaExportResult {
    data object Success : MediaExportResult
    data object SecretBlocked : MediaExportResult
    data class Failure(val cause: Throwable? = null) : MediaExportResult

    val isSuccess: Boolean get() = this is Success
}
