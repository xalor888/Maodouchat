package com.maodouchat.domain.messaging

// 打字信号判定：由 :core:realtime 迁入，唯一生产调用方是 ChatRealtimeController。
enum class TypingSignalAction { START, STOP, NONE }

fun resolveTypingSignalAction(isAnnounced: Boolean, hasInput: Boolean): TypingSignalAction =
    when {
        hasInput && !isAnnounced -> TypingSignalAction.START
        !hasInput && isAnnounced -> TypingSignalAction.STOP
        else -> TypingSignalAction.NONE
    }
