package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiContextMessage
import com.maodouchat.core.model.agentRewriteInstruction

// 本机模型文本任务的纯文本装配，自 app 侧 LocalAiTextTasks 收敛；
// 端点选择与模型调用仍在 app 侧，这里只定对话转录与改写指令。
object AgentTextTaskPolicy {

    fun transcriptLine(sender: String, text: String): String = "$sender: $text"

    fun transcript(messages: List<AiContextMessage>): String =
        messages.joinToString("\n") { transcriptLine(it.sender, it.text) }

    fun rewriteInstruction(mode: String, targetLanguage: String?, style: String?): String =
        buildString {
            append(agentRewriteInstruction(mode, targetLanguage))
            if (!style.isNullOrBlank()) append(" ").append(style)
        }
}
