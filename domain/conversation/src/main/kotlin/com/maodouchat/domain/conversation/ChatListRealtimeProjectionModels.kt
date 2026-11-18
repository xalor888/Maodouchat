package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem

data class AdminBroadcastProjection(
    val item: NotificationCenterItem,
    val bannerText: String,
)
