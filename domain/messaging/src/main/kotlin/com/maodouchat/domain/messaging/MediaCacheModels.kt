package com.maodouchat.domain.messaging

data class LocalFileMetadata(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)
