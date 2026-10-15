package com.maodouchat.util

data class PreviewSnapshot(
    val filePath: String,
    val durationMs: Long,
)

/**
 * 上滑取消已由手势处理；此处描述录制 UI 提示文案 key 语义。
 */
enum class HoldHint {
    RELEASE_TO_PREVIEW,
    SLIDE_UP_CANCEL,
}
