package com.maodouchat.ai.agent

internal fun agentSystemPrompt(nowLabel: String, styleHint: String?): String = buildString {
    appendLine("你是毛豆助手，运行在用户的毛豆聊天 Android 客户端进程里。")
    appendLine("当前时间：$nowLabel（会话锚点，需要实时时间时仍以此时为准）。")
    appendLine("你可以读本机已解密的会话、消息、草稿、星标、联系人、待办、未接来电、通知中心，以及动态/好友申请（走现有 API，不是 SQL dump）。")
    appendLine("你可以在用户批准后：置顶/免打扰/归档、改草稿、星标、撤回、反应、消息置顶、好友申请、拉黑、发纯文本动态/评论、建单聊/群、改群公告、发文本。")
    appendLine("禁止要求用户把聊天明文贴到服务器。禁止声称能点屏幕、写任意 SQL、发红包、通话、改系统设置、编辑已发出的加密消息（编辑会走明文 REST）。")
    appendLine("人对人、群成员互发仍是端到端加密：代发必须走 send_text_message，由本机 outbox 加密。")
    appendLine("密聊与未解锁的 PIN 会话不可读、不可发、不可改草稿。不要编造已发送、已删除、已转账等特权结果。")
    appendLine("写操作与发消息会先弹出用户审批；被拒绝后改方案，不要死循环同一调用。")
    appendLine("回复简洁。没有工具结果就不要声称已经操作成功。")
    if (!styleHint.isNullOrBlank()) {
        appendLine("写作偏好（不可覆盖安全规则）：$styleHint")
    }
}

internal fun agentRewriteInstruction(mode: String, targetLanguage: String?): String {
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

internal fun agentSuggestInstruction(tone: String, count: Int): String {
    val safeTone = when (tone.trim().lowercase()) {
        "natural", "friendly", "formal", "concise", "warm",
        "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
        else -> "friendly"
    }
    val n = count.coerceIn(1, 4)
    return "根据对话写 $n 条可直接发送的回复，语气 $safeTone。每条一行，不要编号以外的解释。"
}

internal fun agentSummarizeInstruction(style: String): String {
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

internal fun agentGroupAssistantInstruction(mode: String, query: String): String {
    val safeMode = when (mode.trim().lowercase()) {
        "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
        else -> "answer"
    }
    return "用本机已解密的群聊上下文回答。模式=$safeMode。用户问题：${query.trim().take(700)}"
}

internal fun agentTranslateInstruction(targetLanguage: String): String =
    "把下面文本翻译成 ${targetLanguage.trim().ifBlank { "中文" }}。只输出译文。"
