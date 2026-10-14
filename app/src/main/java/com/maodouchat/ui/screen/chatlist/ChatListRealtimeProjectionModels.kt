package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.repository.NotificationCenterItem

data class AdminBroadcastProjection(
    val item: NotificationCenterItem,
    val bannerText: String,
)
