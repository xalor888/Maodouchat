package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AgentToolPolicy
import com.maodouchat.core.model.AiPromptSafetyPolicy
import com.maodouchat.core.model.Message

// Agent 消息行的纯格式化逻辑，自 app 侧 ai/agent 消息读簇收敛；
// 查库与门闩仍在 app 侧，这里只定行格式与条数边界。
object AgentMessageLinePolicy {

    fun formatMessage(message: Message): String {
        val text = AiPromptSafetyPolicy.sanitizeContextText(message.parsedContent(), 500)
        return "${message.timestamp}\t${message.senderId}\t${message.type.name}\t$text"
    }

    fun searchHitLine(chatId: String, messageId: String, searchableText: String): String =
        "$chatId\t$messageId\t${AgentReadPreviewPolicy.searchHitText(searchableText)}"

    fun starredLine(chatId: String, id: String, content: String): String {
        val text = AiPromptSafetyPolicy.sanitizeContextText(content, 160)
        return "$chatId\t$id\t$text"
    }

    fun searchLimit(limit: Int): Int = limit.coerceIn(1, AgentToolPolicy.MAX_SEARCH_HITS)

    fun starredLimit(limit: Int): Int = limit.coerceIn(1, 40)
}
