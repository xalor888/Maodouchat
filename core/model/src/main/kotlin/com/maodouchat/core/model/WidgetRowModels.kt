package com.maodouchat.core.model

data class WidgetRowLabels(
    val appName: String,
    val genericPreview: String,
    val lockedPreview: String,
    val attachmentLabel: String,
)

data class WidgetChatInput(
    val chat: Chat,
    // 隐私判定由调用方按 :domain:messaging 的 ConversationPrivacyPolicy 预计算传入，
    // 行投影本身只依赖这两个布尔值，不直接依赖 domain 层。
    val previewAllowed: Boolean,
    val isLocked: Boolean,
)

data class WidgetRowsResult(
    val rows: List<WidgetRow>,
    val totalUnread: Int,
)
