package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User

// 消息列表派生量族里的纯派生：id 索引、发送者名映射、选中集与多选态。
// chatItems/reversedChatItems 需 Context + formatDateLabel，留在 app 侧。

fun resolveMessagesById(messages: List<Message>): Map<String, Message> =
    messages.associateBy(Message::id)

fun resolveParticipantNamesById(
    participants: List<User>?,
    memberNicknameByUser: Map<String, String>,
    chatIsGroup: Boolean,
): Map<String, String> {
    val base = participants.orEmpty().associate { it.id to it.displayName }
    // 0.69 修复：群聊优先使用群内昵称（此前群昵称只作用于群成员列表，消息不生效）。
    return if (chatIsGroup && memberNicknameByUser.isNotEmpty()) {
        base + memberNicknameByUser
    } else {
        base
    }
}

fun resolveSelectedMessages(
    messages: List<Message>,
    selectedMessageIds: Set<String>,
): List<Message> = messages.filter { it.id in selectedMessageIds }

fun isMessageSelectionMode(selectedMessageIds: Set<String>): Boolean =
    selectedMessageIds.isNotEmpty()
