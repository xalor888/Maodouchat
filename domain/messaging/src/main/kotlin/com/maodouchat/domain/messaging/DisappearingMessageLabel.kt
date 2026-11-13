package com.maodouchat.domain.messaging

// 阅后即焚时长档位：秒数 → 文案种类；R.string 映射留在 app 侧 @Composable 壳里。
enum class DisappearDurationLabel {
    Off, Seconds30, Minute1, Minute2, Minute5, Minute15,
    Hour1, Hour2, Hour4, Hour8, Hour12, Hour24, Days7, Days30
}

object DisappearingMessageLabel {

    // 原 disappearSecondsLabel 的 when 分支逐字平移，未列出的秒数落回 Off。
    fun disappearDurationLabel(seconds: Int): DisappearDurationLabel = when (seconds) {
        0 -> DisappearDurationLabel.Off
        30 -> DisappearDurationLabel.Seconds30
        60 -> DisappearDurationLabel.Minute1
        2 * 60 -> DisappearDurationLabel.Minute2
        5 * 60 -> DisappearDurationLabel.Minute5
        15 * 60 -> DisappearDurationLabel.Minute15
        60 * 60 -> DisappearDurationLabel.Hour1
        2 * 60 * 60 -> DisappearDurationLabel.Hour2
        4 * 60 * 60 -> DisappearDurationLabel.Hour4
        8 * 60 * 60 -> DisappearDurationLabel.Hour8
        12 * 60 * 60 -> DisappearDurationLabel.Hour12
        24 * 60 * 60 -> DisappearDurationLabel.Hour24
        7 * 24 * 60 * 60 -> DisappearDurationLabel.Days7
        30 * 24 * 60 * 60 -> DisappearDurationLabel.Days30
        else -> DisappearDurationLabel.Off
    }
}
