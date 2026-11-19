package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentChatMessage

// 会话标题推导：首条用户消息正文截断，未发过言则沿用原标题，自 app 侧 MaodouAgentService 收敛。
object AgentSessionTitlePolicy {

    const val DEFAULT_TITLE: String = "新对话"

    const val MAX_SESSION_TITLE_CHARS: Int = 24

    fun deriveTitle(messages: List<AgentChatMessage>, currentTitle: String): String =
        messages.firstOrNull { it.role == "user" }?.content?.take(MAX_SESSION_TITLE_CHARS) ?: currentTitle
}
