package com.maodouchat.slim

/** 贴纸包下载结果（含面向 UI 的状态文案，字符串资源见 values/values-en）。 */
data class StickerPackDownloadResult(
    val packId: String,
    val downloaded: Int,
    val failed: Int,
    val message: String?,
)
