package com.maodouchat.widget

import com.maodouchat.data.model.Chat
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities

data class WidgetRowLabels(
    val appName: String,
    val genericPreview: String,
    val lockedPreview: String,
    val attachmentLabel: String,
)

data class WidgetChatInput(
    val chat: Chat,
    val caps: ConversationPrivacyCapabilities,
)

data class WidgetRowsResult(
    val rows: List<ConversationWidgetData.WidgetRow>,
    val totalUnread: Int,
)
