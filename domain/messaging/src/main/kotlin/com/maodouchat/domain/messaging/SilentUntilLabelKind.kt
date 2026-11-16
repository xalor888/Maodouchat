package com.maodouchat.domain.messaging

// 静音至档位文案种类；R.string 映射留在 app 侧。
enum class SilentUntilLabelKind {
    HOUR_1,
    HOURS_8,
    HOURS_24,
}

fun silentUntilLabelKind(option: SilentUntilOption): SilentUntilLabelKind =
    when (option) {
        SilentUntilOption.Hour1 -> SilentUntilLabelKind.HOUR_1
        SilentUntilOption.Hours8 -> SilentUntilLabelKind.HOURS_8
        SilentUntilOption.Hours24 -> SilentUntilLabelKind.HOURS_24
    }
