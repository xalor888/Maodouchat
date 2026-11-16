package com.maodouchat.domain.calls

import com.maodouchat.core.model.State

// 未接来电行后缀种类；已接且时长文案为空时无后缀，与原分支一致。
enum class MissedCallSuffixKind {
    BADGE,
    DURATION,
    NONE,
}

fun missedCallSuffixKind(state: State, durationText: String): MissedCallSuffixKind =
    when (state) {
        State.MISSED -> MissedCallSuffixKind.BADGE
        State.ANSWERED -> if (durationText.isNotEmpty()) MissedCallSuffixKind.DURATION else MissedCallSuffixKind.NONE
    }
