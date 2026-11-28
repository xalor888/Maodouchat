package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentToolLimitPolicy
import com.maodouchat.domain.messaging.AgentToolNamePolicy
import com.maodouchat.domain.messaging.AgentToolPreview

// Agent 工具分发：消息簇（会话/消息/发送/群组）。从 AgentToolHost.execute/preview 按簇搬出，分支逐字一致。
internal object AgentMessagingToolHost {
    internal suspend fun execute(name: String, ports: AgentToolPorts, userId: String, args: Map<String, String>): String? = when (name) {
        AgentToolNamePolicy.LIST_CHATS -> AgentMessagingChatReads.listChats(ports, args["query"])
        AgentToolNamePolicy.GET_CHAT_HISTORY -> AgentMessagingChatReads.getChatHistory(ports, args["chatId"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.GET_CHAT_HISTORY))
        AgentToolNamePolicy.SEARCH_MESSAGES -> AgentMessagingMessageReads.searchMessages(ports, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.SEARCH_MESSAGES))
        AgentToolNamePolicy.GET_CHAT -> AgentMessagingChatReads.getChat(ports, args["chatId"].orEmpty())
        AgentToolNamePolicy.LIST_STARRED_MESSAGES -> AgentMessagingMessageReads.listStarred(ports, args["limit"]?.toIntOrNull() ?: AgentToolLimitPolicy.defaultLimit(AgentToolNamePolicy.LIST_STARRED_MESSAGES))
        AgentToolNamePolicy.LIST_PINNED_MESSAGES -> AgentMessagingMessageReads.listPinned(ports, args["chatId"].orEmpty())
        AgentToolNamePolicy.SEND_TEXT_MESSAGE -> AgentMessagingSendTools.sendText(ports, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
        AgentToolNamePolicy.UPDATE_CHAT -> AgentMessagingChatTools.updateChat(ports, args)
        AgentToolNamePolicy.SET_DRAFT -> AgentMessagingSendTools.setDraft(ports, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
        AgentToolNamePolicy.STAR_MESSAGE -> AgentMessagingMessageTools.starMessage(ports, args["messageId"].orEmpty(), AgentToolHost.parseBool(args["starred"]))
        AgentToolNamePolicy.DELETE_LOCAL_MESSAGE -> AgentMessagingMessageTools.deleteLocalMessage(ports, args["messageId"].orEmpty())
        AgentToolNamePolicy.SET_CONTACT_NICKNAME -> AgentMessagingChatTools.setNickname(ports, args["userId"].orEmpty(), args["nickname"].orEmpty())
        AgentToolNamePolicy.MARK_NOTIFICATION_READ -> AgentMessagingChatTools.markNotificationRead(ports, args["itemId"].orEmpty())
        AgentToolNamePolicy.REVOKE_MESSAGE -> AgentMessagingMessageTools.revokeMessage(ports, args["messageId"].orEmpty())
        AgentToolNamePolicy.REACT_TO_MESSAGE -> AgentMessagingMessageTools.react(ports, args["messageId"].orEmpty(), args["emoji"].orEmpty())
        AgentToolNamePolicy.PIN_MESSAGE -> AgentMessagingMessageTools.pinMessage(ports, args["chatId"].orEmpty(), args["messageId"].orEmpty())
        AgentToolNamePolicy.CREATE_DIRECT_CHAT -> AgentGroupWriteTools.createDirect(ports, args["userId"].orEmpty())
        AgentToolNamePolicy.CREATE_GROUP -> AgentGroupWriteTools.createGroup(ports, args["name"].orEmpty(), args["memberIds"].orEmpty())
        AgentToolNamePolicy.RENAME_GROUP -> AgentGroupWriteTools.renameGroup(ports, args["chatId"].orEmpty(), args["name"].orEmpty())
        AgentToolNamePolicy.UPDATE_GROUP_ANNOUNCEMENT -> AgentGroupWriteTools.updateAnnouncement(ports, args["chatId"].orEmpty(), args["announcement"].orEmpty())
        AgentToolNamePolicy.ADD_GROUP_MEMBERS -> AgentGroupWriteTools.addMembers(ports, args["chatId"].orEmpty(), args["memberIds"].orEmpty())
        AgentToolNamePolicy.REMOVE_GROUP_MEMBER -> AgentGroupWriteTools.removeMember(ports, args["chatId"].orEmpty(), args["memberId"].orEmpty())
        AgentToolNamePolicy.MUTE_GROUP_MEMBER -> AgentGroupWriteTools.muteMember(ports, args["chatId"].orEmpty(), args["memberId"].orEmpty(), args["mutedUntil"]?.toLongOrNull() ?: 0L)
        AgentToolNamePolicy.DELETE_CHAT -> AgentGroupWriteTools.deleteChat(ports, args["chatId"].orEmpty())
        else -> null
    }

    internal fun preview(name: String, args: Map<String, String>): String? =
        AgentToolPreview.preview(name, args)
}
