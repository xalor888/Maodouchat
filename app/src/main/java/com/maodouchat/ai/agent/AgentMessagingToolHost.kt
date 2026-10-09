package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp

// Agent 工具分发：消息簇（会话/消息/发送/群组）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentMessagingToolHost {
    internal suspend fun execute(name: String, app: MaodouchatApp, userId: String, args: Map<String, String>): String? = when (name) {
        "list_chats" -> AgentMessagingChatReads.listChats(app, args["query"])
        "get_chat_history" -> AgentMessagingChatReads.getChatHistory(app, args["chatId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
        "search_messages" -> AgentMessagingMessageReads.searchMessages(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 12)
        "get_chat" -> AgentMessagingChatReads.getChat(app, args["chatId"].orEmpty())
        "list_starred_messages" -> AgentMessagingMessageReads.listStarred(app, args["limit"]?.toIntOrNull() ?: 20)
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

    internal fun preview(name: String, args: Map<String, String>): String? = when (name) {
        "send_text_message" ->
            "发到 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(160)}"
        "update_chat" ->
            "改会话 ${args["chatId"].orEmpty().take(24)} pinned=${args["pinned"]} muted=${args["muted"]} archived=${args["archived"]} unread=${args["markedUnread"]}"
        "set_draft" ->
            "草稿 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(80).ifBlank { "(清空)" }}"
        "star_message" ->
            "星标 ${args["messageId"].orEmpty().take(24)} → ${args["starred"]}"
        "set_contact_nickname" ->
            "备注 ${args["userId"].orEmpty().take(24)} → ${args["nickname"].orEmpty().take(40).ifBlank { "(清除)" }}"
        "delete_local_message" ->
            "本机删除消息 ${args["messageId"].orEmpty().take(24)}"
        "mark_notification_read" ->
            "通知已读 ${args["itemId"].orEmpty().take(24)}"
        "revoke_message" -> "撤回 ${args["messageId"].orEmpty().take(24)}"
        "react_to_message" -> "反应 ${args["emoji"].orEmpty()} → ${args["messageId"].orEmpty().take(24)}"
        "pin_message" -> "置顶消息 ${args["messageId"].orEmpty().take(24)}"
        "create_direct_chat" -> "开聊 ${args["userId"].orEmpty().take(24)}"
        "create_group" -> "建群 ${args["name"].orEmpty().take(40)}"
        "rename_group" -> "改群名 ${args["name"].orEmpty().take(40)}"
        "add_group_members" -> "拉人进群 ${args["memberIds"].orEmpty().take(80)}"
        "remove_group_member" -> "移出群 ${args["memberId"].orEmpty().take(24)}"
        "mute_group_member" -> "禁言 ${args["memberId"].orEmpty().take(24)}"
        "delete_chat" -> "删除会话 ${args["chatId"].orEmpty().take(24)}"
        else -> null
    }
}
