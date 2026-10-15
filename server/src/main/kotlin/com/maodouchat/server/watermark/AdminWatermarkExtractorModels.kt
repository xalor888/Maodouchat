package com.maodouchat.server.watermark

data class ExtractResult(
    val found: Boolean,
    val payloadHex: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val message: String = ""
)
