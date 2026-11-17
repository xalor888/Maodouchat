package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiContextMessage
import com.maodouchat.core.model.agentRewriteInstruction

// 本机模型文本任务的纯文本装配，自 app 侧 LocalAiTextTasks 收敛；
// 端点选择与模型调用仍在 app 侧，这里只定对话转录与任务指令。
// 翻译/建议/摘要/群助手四项提示逐字自 :core:model 的 AgentTextTaskPrompts 迁入。
object AgentTextTaskPolicy {

    fun transcriptLine(sender: String, text: String): String = "$sender: $text"

    fun transcript(messages: List<AiContextMessage>): String =
        messages.joinToString("\n") { transcriptLine(it.sender, it.text) }

    fun rewriteInstruction(mode: String, targetLanguage: String?, style: String?): String =
        buildString {
            append(agentRewriteInstruction(mode, targetLanguage))
            if (!style.isNullOrBlank()) append(" ").append(style)
        }

    fun translateInstruction(targetLanguage: String): String =
        "把下面文本翻译成 ${targetLanguage.trim().ifBlank { "中文" }}。只输出译文。"

    fun suggestInstruction(tone: String, count: Int): String {
        val safeTone = when (tone.trim().lowercase()) {
            "natural", "friendly", "formal", "concise", "warm",
            "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
            else -> "friendly"
        }
        val n = count.coerceIn(1, 4)
        return "根据对话写 $n 条可直接发送的回复，语气 $safeTone。每条一行，不要编号以外的解释。"
    }

    fun summarizeInstruction(style: String): String {
        val safe = when (style.trim().lowercase()) {
            "detailed" -> "详细叙述"
            "decisions" -> "只列已做出的决定"
            "tasks" -> "只列待办，每行一条"
            "timeline" -> "按时间顺序列要点"
            "risks" -> "只列风险和阻塞"
            else -> "简要概括"
        }
        return "总结这些本机已解密消息。风格：$safe。不要声称你已在聊天里执行了任何操作。"
    }

    fun groupAssistantInstruction(mode: String, query: String): String {
        val safeMode = when (mode.trim().lowercase()) {
            "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
            else -> "answer"
        }
        return "用本机已解密的群聊上下文回答。模式=$safeMode。用户问题：${query.trim().take(700)}"
    }
}
