package com.maodouchat.domain.messaging

// 公告级别文案种类；R.string 映射留在 app 侧。
enum class AnnouncementLevelKind {
    EMERGENCY,
    MAINTENANCE,
    INFO,
}

fun announcementLevelKind(level: String): AnnouncementLevelKind =
    when (level) {
        "EMERGENCY" -> AnnouncementLevelKind.EMERGENCY
        "MAINTENANCE" -> AnnouncementLevelKind.MAINTENANCE
        else -> AnnouncementLevelKind.INFO
    }
