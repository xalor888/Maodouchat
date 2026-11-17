package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiContextMessage

// 本机模型文本任务的纯文本装配，自 app 侧 LocalAiTextTasks 收敛；
// 端点选择与模型调用仍在 app 侧，这里只定对话转录与任务指令。
// 翻译/建议/摘要/群助手四项提示与改写基础指令逐字自 :core:model 的 AgentTextTaskPrompts 迁入，该文件随之删除。
object AgentTextTaskPolicy {

    fun transcriptLine(sender: String, text: String): String = "$sender: $text"

    fun transcript(messages: List<AiContextMessage>): String =
        messages.joinToString("\n") { transcriptLine(it.sender, it.text) }

    fun rewriteBaseInstruction(mode: String, targetLanguage: String?): String {
        val safe = when (mode.trim().lowercase()) {
            "shorten" -> "缩短，保留原意"
            "formal" -> "更正式礼貌"
            "gentle" -> "更温和"
            "casual" -> "更口语"
            "professional" -> "更专业商务"
            "expand" -> "稍加展开，不编造事实"
            "bullet" -> "改成简洁条目"
            "clarify" -> "更清楚，不改变立场"
            "translate" -> "翻译成 ${targetLanguage?.trim().orEmpty().ifBlank { "中文" }}，只输出译文"
            else -> "润色，保持原意和语气"
        }
        return "改写下面的草稿：$safe。只输出改写结果，不要解释。"
    }

    fun rewriteInstruction(mode: String, targetLanguage: String?, style: String?): String =
        buildString {
            append(rewriteBaseInstruction(mode, targetLanguage))
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
