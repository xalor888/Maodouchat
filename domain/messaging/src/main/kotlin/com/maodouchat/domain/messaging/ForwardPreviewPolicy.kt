package com.maodouchat.domain.messaging

// 转发预览/附言/分页常量：预览条数、预览文案长度、附言上限与目标分页大小。
object ForwardPreviewPolicy {

    const val PAGE_SIZE: Int = 64

    const val PREVIEW_COUNT: Int = 3

    const val RECENT_CHIPS_MAX: Int = 6

    const val PREVIEW_TEXT_LENGTH: Int = 40

    const val NOTE_MAX_LENGTH: Int = 500

    // 预览文案：换行折为空格后截 40 字符（输入已由调用方脱敏）。
    fun previewText(content: String): String = content.replace('\n', ' ').take(PREVIEW_TEXT_LENGTH)

    // 附言输入过滤：截 500 字符。
    fun filterNote(raw: String): String = raw.take(NOTE_MAX_LENGTH)

    // 折叠的预览条数：超过 3 条的部分。
    fun previewOverflowCount(total: Int): Int = (total - PREVIEW_COUNT).coerceAtLeast(0)

    // 折叠的转发目标数：超过一页的部分。
    fun hiddenTargetCount(total: Int): Int = (total - PAGE_SIZE).coerceAtLeast(0)
}
