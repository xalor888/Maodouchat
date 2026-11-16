package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat

// 会话列表行头像：密聊不显示头像；群聊用群头像、单聊用对方头像；在线绿点只看对方是否在线，频道沿用非群分支。
object ChatListAvatarPolicy {

    fun avatarUrl(chat: Chat, isSecret: Boolean): String? =
        if (isSecret || chat.isSecret) null
        else if (chat.isGroup) chat.groupAvatar
        else chat.participants.firstOrNull()?.avatar

    fun showOnlineDot(chat: Chat, isSecret: Boolean): Boolean =
        !chat.isGroup && chat.participants.firstOrNull()?.isOnline == true && !isSecret
}
