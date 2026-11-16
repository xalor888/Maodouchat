package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat

// 复制会话名称：频道/群组取群名，私聊取首个参与者展示名。
fun Chat.copyableName(): String =
    when {
        isChannel -> groupName?.takeIf(String::isNotBlank) ?: ""
        isGroup -> groupName?.takeIf(String::isNotBlank) ?: ""
        else -> participants.firstOrNull()?.displayName.orEmpty()
    }
