package com.maodouchat.domain.messaging

import com.maodouchat.core.model.VISIBILITY_VALUES

// Agent 工具写操作入口的输入清洗，自 app 侧 ai/agent 写操作簇收敛；
// 网络/数据库调用仍留在 app 侧。
object AgentWriteInputPolicy {

    fun cleanSendText(text: String): String =
        text.trim().take(AgentToolPolicy.MAX_TEXT_SEND_CHARS)

    fun cleanDraftText(text: String): String =
        text.trim().take(AgentToolPolicy.MAX_DRAFT_CHARS)

    fun cleanNickname(nickname: String): String =
        nickname.trim().take(AgentToolPolicy.MAX_NICKNAME_CHARS)

    fun parseMemberIds(raw: String): List<String> =
        raw.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun clampMuteUntil(mutedUntil: Long): Long = mutedUntil.coerceAtLeast(0L)

    fun resolvePostVisibility(visibility: String?): String? =
        visibility?.trim()?.uppercase()?.takeIf { it in VISIBILITY_VALUES }
}
