package com.maodouchat.explore.repository

enum class UploadStatus {
    IDLE,
    QUEUED,
    UPLOADING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class UploadItem(
    val id: String,
    val ownerUserId: String,
    val base64Data: String,
    val status: UploadStatus = UploadStatus.QUEUED,
    val uploadUrl: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)
