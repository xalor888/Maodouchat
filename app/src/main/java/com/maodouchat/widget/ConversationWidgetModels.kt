package com.maodouchat.widget

// ---- 模型 ----
data class WidgetConfig(
    val chatIds: List<String> = emptyList(),
    val showUnreadBadge: Boolean = true,
    val compact: Boolean = false,
)

data class WidgetRow(
    val chatId: String,
    val title: String,
    val subtitle: String,
    val timeLabel: String,
    val unread: Int,
)

data class WidgetSnapshot(
    val rows: List<WidgetRow>,
    val totalUnread: Int,
    val ownerUserId: String,
    val signedOut: Boolean,
    val updatedAt: Long,
)
