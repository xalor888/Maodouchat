package com.maodouchat.domain.messaging

// 免打扰预置时段文案种类；R.string 映射留在 app 侧。
enum class QuietHoursPresetLabelKind {
    NIGHT,
    LUNCH,
    SLEEP,
    WORKDAY,
}

fun quietHoursPresetLabelKind(preset: QuietHoursPreset): QuietHoursPresetLabelKind =
    when (preset) {
        QuietHoursPreset.Night -> QuietHoursPresetLabelKind.NIGHT
        QuietHoursPreset.Lunch -> QuietHoursPresetLabelKind.LUNCH
        QuietHoursPreset.Sleep -> QuietHoursPresetLabelKind.SLEEP
        QuietHoursPreset.Workday -> QuietHoursPresetLabelKind.WORKDAY
    }
