package com.maodouchat.domain.messaging

// 复制文案拼装：纯字符串拼接，剪贴板写入与 Toast 仍在 app 侧。
fun copyMessageLabelWithSender(sender: String, body: String): String =
    if (sender.isBlank()) body else "$sender: $body"

fun copyMessageLabelWithSenderTime(time: String, sender: String, body: String): String =
    when {
        body.isBlank() -> time
        sender.isBlank() -> "$time $body"
        else -> "$time $sender: $body"
    }
