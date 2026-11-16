package com.maodouchat.domain.conversation

// 截屏告警文案组合种类；调用方只在至少一种守卫激活时运行，else 分支恒为仅阅后即焚。
enum class ScreenshotAlertKind {
    BOTH,
    SECRET_ONLY,
    DISAPPEARING_ONLY,
}

fun screenshotAlertKind(secretActive: Boolean, disappearingActive: Boolean): ScreenshotAlertKind =
    when {
        secretActive && disappearingActive -> ScreenshotAlertKind.BOTH
        secretActive -> ScreenshotAlertKind.SECRET_ONLY
        else -> ScreenshotAlertKind.DISAPPEARING_ONLY
    }
