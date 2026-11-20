package com.maodouchat.core.crypto

import java.io.File

data class EncryptedFile(
    val file: File,
    val keyBase64: String,
    val ivBase64: String,
    val cipherSha256: String,
    val plainSha256: String,
    val cipherSize: Long,
    val plainSize: Long
)
