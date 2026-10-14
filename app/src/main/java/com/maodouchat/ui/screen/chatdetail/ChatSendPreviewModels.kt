package com.maodouchat.ui.screen.chatdetail

/**
 * 待发送的媒体（G79 从 `ChatDetailRoute.kt` 连同预览对话框一起搬来）。
 *
 * 图片与视频预览共用同一结构：`viewOnce`（阅后即焚）与 `spoiler`（剧透模糊）是两个
 * 跨预览/发送的开关，因此随 pending 一起传递，而不是拆成三个平行参数。
 */
internal data class PendingImageSend(
    val uri: android.net.Uri,
    val viewOnce: Boolean,
    val spoiler: Boolean
)
