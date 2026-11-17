package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：消息簇（会话/消息/发送/群组）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentMessagingToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        "list_chats" -> AgentMessagingChatReads.listChats(app, args["query"])
        "get_chat_history" -> AgentMessagingChatReads.getChatHistory(app, args["chatId"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("get_chat_history"))
        "search_messages" -> AgentMessagingMessageReads.searchMessages(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("search_messages"))
        "get_chat" -> AgentMessagingChatReads.getChat(app, args["chatId"].orEmpty())
        "list_starred_messages" -> AgentMessagingMessageReads.listStarred(app, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit("list_starred_messages"))
        "list_pinned_messages" -> AgentMessagingMessageReads.listPinned(app, args["chatId"].orEmpty())
        "send_text_message" -> AgentMessagingSendTools.sendText(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
        "update_chat" -> AgentMessagingChatTools.updateChat(app, args)
        "set_draft" -> AgentMessagingSendTools.setDraft(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
        "star_message" -> AgentMessagingMessageTools.starMessage(app, args["messageId"].orEmpty(), AgentToolHost.parseBool(args["starred"]))
        "delete_local_message" -> AgentMessagingMessageTools.deleteLocalMessage(app, args["messageId"].orEmpty())
        "set_contact_nickname" -> AgentMessagingChatTools.setNickname(app, args["userId"].orEmpty(), args["nickname"].orEmpty())
        "mark_notification_read" -> AgentMessagingChatTools.markNotificationRead(app, args["itemId"].orEmpty())
        "revoke_message" -> AgentMessagingMessageTools.revokeMessage(app, args["messageId"].orEmpty())
        "react_to_message" -> AgentMessagingMessageTools.react(app, args["messageId"].orEmpty(), args["emoji"].orEmpty())
        "pin_message" -> AgentMessagingMessageTools.pinMessage(app, args["chatId"].orEmpty(), args["messageId"].orEmpty())
        "create_direct_chat" -> AgentGroupWriteTools.createDirect(app, args["userId"].orEmpty())
        "create_group" -> AgentGroupWriteTools.createGroup(app, args["name"].orEmpty(), args["memberIds"].orEmpty())
        "rename_group" -> AgentGroupWriteTools.renameGroup(app, args["chatId"].orEmpty(), args["name"].orEmpty())
        "update_group_announcement" -> AgentGroupWriteTools.updateAnnouncement(app, args["chatId"].orEmpty(), args["announcement"].orEmpty())
        "add_group_members" -> AgentGroupWriteTools.addMembers(app, args["chatId"].orEmpty(), args["memberIds"].orEmpty())
        "remove_group_member" -> AgentGroupWriteTools.removeMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty())
        "mute_group_member" -> AgentGroupWriteTools.muteMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty(), args["mutedUntil"]?.toLongOrNull() ?: 0L)
        "delete_chat" -> AgentGroupWriteTools.deleteChat(app, args["chatId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
