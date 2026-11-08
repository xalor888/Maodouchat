package com.maodouchat.core.model

// 桌面小组件行投影纯策略：隐私门禁、脱敏标题/预览、角标、行数上限。
// 密聊 / 隐私受限会话永不上桌；App 锁或 PIN 锁定时标题只显示应用名、正文只显示通用脱敏文案；App 锁定时角标强制 0。
// DB 查询、字符串资源、相对时间格式化仍由调用方提供（timeLabel 以函数传入，保持纯）。

fun widgetChatTitle(chat: Chat, ownerUserId: String): String? {
    if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() }
    return chat.participants
        .firstOrNull { it.id != ownerUserId }
        ?.let { it.displayName.ifBlank { it.name } }
        ?: chat.groupName?.takeIf { it.isNotBlank() }
}

fun widgetPreviewOf(raw: String, type: MessageType, attachmentLabel: String): String {
    if (type != MessageType.TEXT && type != MessageType.MARKDOWN) return attachmentLabel
    val text = raw.substringBefore(Message.META_TAG_PREFIX).trim()
    return if (text.length <= 40) text else text.take(40) + "…"
}

fun buildWidgetRows(
    inputs: List<WidgetChatInput>,
    ownerUserId: String,
    showUnreadBadge: Boolean,
    appLockOn: Boolean,
    labels: WidgetRowLabels,
    maxRows: Int,
    timeLabel: (Long) -> String,
): WidgetRowsResult {
    val rows = ArrayList<WidgetRow>(maxRows.coerceAtLeast(0))
    var totalUnread = 0
    for (input in inputs) {
        if (rows.size >= maxRows) break
        val chat = input.chat
        if (chat.id.isBlank()) continue
        // 密聊 / 隐私受限会话：永不展示在桌面小组件
        if (!input.previewAllowed) {
            continue
        }
        val pinLocked = input.isLocked
        val title = if (appLockOn || pinLocked) {
            labels.appName
        } else {
            widgetChatTitle(chat, ownerUserId) ?: chat.id.take(12)
        }
        val subtitle = when {
            pinLocked -> labels.lockedPreview
            appLockOn -> labels.genericPreview
            else -> widgetPreviewOf(chat.lastMessage, chat.lastMessageType, labels.attachmentLabel)
        }
        val unread = if (appLockOn || !showUnreadBadge) 0 else chat.unreadCount
        totalUnread += unread
        rows += WidgetRow(
            chatId = chat.id,
            title = title,
            subtitle = subtitle,
            timeLabel = timeLabel(chat.lastMessageTime),
            unread = unread,
        )
    }
    return WidgetRowsResult(rows, totalUnread)
}
