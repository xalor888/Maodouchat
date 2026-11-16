package com.maodouchat.domain.groups

// 群公告输入上限：与服务端 /api/chats/{chatId}/announcement 的 1200 字校验一致。
object GroupAnnouncementPolicy {

    const val MAX_LENGTH: Int = 1200

    // 输入过滤：截 1200 字符，不做 trim（与原输入框行为一致）。
    fun filterInput(raw: String): String = raw.take(MAX_LENGTH)
}
