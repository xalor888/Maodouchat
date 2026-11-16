package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User

// 名片选择器目标：滤掉群聊，取每会话除自己外的首个参与者，
// 按对端显示名小写排序；自 ChatDetailComposerOverlays 的 pickerContacts 收敛。
object ContactCardTargetPolicy {

    fun pickTargets(chats: List<Chat>, currentUserId: String): List<Pair<Chat, User>> =
        chats
            .filter { !it.isGroup }
            .mapNotNull { chat ->
                val other = chat.participants.firstOrNull { it.id != currentUserId }
                if (other == null) null else chat to other
            }
            .sortedBy { it.second.displayName.lowercase() }
}
