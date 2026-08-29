package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class AttachmentUploadResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val expiresAt: Long
)

@Serializable
data class AttachmentUploadSessionRequest(
    val chatId: String,
    val messageId: String,
    val cipherSha256: String,
    val cipherSize: Long
)

@Serializable
data class AttachmentUploadStatusResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val uploadedBytes: Long,
    val status: String,
    val expiresAt: Long,
    val complete: Boolean
)
