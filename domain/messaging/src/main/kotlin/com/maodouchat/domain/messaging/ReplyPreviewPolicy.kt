package com.maodouchat.domain.messaging

// 回复/引用预览截断：时间线引用气泡与输入区引用提示条共用同一 60 字符上限。
object ReplyPreviewPolicy {

    const val MAX_CHARS = 60

    fun preview(text: String): String = text.take(MAX_CHARS)
}
