package com.maodouchat.domain.conversation

data class MediaExportRequest(
    val rawUri: String,
    val mimeType: String,
    val displayName: String,
    val isSecretChat: Boolean,
    val chooserTitle: String = "",
)

sealed interface MediaExportResult {
    data object Success : MediaExportResult
    data object SecretBlocked : MediaExportResult
    data class Failure(val cause: Throwable? = null) : MediaExportResult

    val isSuccess: Boolean get() = this is Success
}
