package com.maodouchat.domain.messaging

// 发送前预览的发送模式：阅后即焚优先于剧透，剧透优先于普通发送。
enum class MediaSendModeKind {
    VIEW_ONCE,
    SPOILER,
    NORMAL,
}

fun mediaSendMode(viewOnce: Boolean, spoiler: Boolean): MediaSendModeKind =
    when {
        viewOnce -> MediaSendModeKind.VIEW_ONCE
        spoiler -> MediaSendModeKind.SPOILER
        else -> MediaSendModeKind.NORMAL
    }
