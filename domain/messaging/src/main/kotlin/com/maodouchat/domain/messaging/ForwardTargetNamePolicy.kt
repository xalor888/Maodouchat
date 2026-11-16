package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat

// 转发目标名解析：纯判定；群人数复数文案与私聊兜底文案由调用方取字符串资源。
sealed interface ForwardTargetName {
    data class GroupNamed(val name: String) : ForwardTargetName
    data class GroupCount(val count: Int) : ForwardTargetName
    data class Private(val name: String) : ForwardTargetName
    object PrivateFallback : ForwardTargetName
}

object ForwardTargetNamePolicy {

    // 群聊：有群名直接用（空串也照用，只判 null）；无群名回退到人数。
    // 单聊：首个非己参与者的展示名；没有对方回落兜底。
    fun resolve(chat: Chat, currentUserId: String): ForwardTargetName {
        if (chat.isGroup) {
            val name = chat.groupName
            if (name != null) return ForwardTargetName.GroupNamed(name)
            return ForwardTargetName.GroupCount(chat.participants.size)
        }
        val peer = chat.participants.firstOrNull { it.id != currentUserId }
        if (peer != null) return ForwardTargetName.Private(peer.displayName)
        return ForwardTargetName.PrivateFallback
    }
}
