package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import org.json.JSONObject

object AgentToolHost {
    suspend fun execute(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        val app = MaodouchatApp.instance
        val userId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (userId.isBlank()) return "Error: not signed in"
        return when (name) {
            "list_chats" -> AgentMessagingChatReads.listChats(app, args["query"])
            "get_chat_history" -> AgentMessagingChatReads.getChatHistory(app, args["chatId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
            "search_messages" -> AgentMessagingMessageReads.searchMessages(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 12)
            "get_contacts" -> AgentSocialReadTools.getContacts(app, args["query"])
            "get_me" -> AgentSocialReadTools.getMe(app, userId)
            "get_chat" -> AgentMessagingChatReads.getChat(app, args["chatId"].orEmpty())
            "list_starred_messages" -> AgentMessagingMessageReads.listStarred(app, args["limit"]?.toIntOrNull() ?: 20)
            "list_drafts" -> AgentTaskReadTools.listDrafts(app, userId)
            "get_draft" -> AgentTaskReadTools.getDraft(app, userId, args["chatId"].orEmpty())
            "list_local_tasks" -> AgentTaskReadTools.listTasks(app, args["limit"]?.toIntOrNull() ?: 20)
            "list_missed_calls" -> AgentTaskReadTools.listMissedCalls(app, args["limit"]?.toIntOrNull() ?: 12)
            "list_notifications" -> AgentTaskReadTools.listNotifications(app, args["limit"]?.toIntOrNull() ?: 20)
            "create_local_task" -> AgentTaskWriteTools.createTask(app, args["chatId"].orEmpty(), args["title"].orEmpty(), args["dueText"])
            "send_text_message" -> AgentMessagingSendTools.sendText(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
            "update_chat" -> AgentMessagingChatTools.updateChat(app, args)
            "set_draft" -> AgentMessagingSendTools.setDraft(app, userId, args["chatId"].orEmpty(), args["text"].orEmpty())
            "star_message" -> AgentMessagingMessageTools.starMessage(app, args["messageId"].orEmpty(), parseBool(args["starred"]))
            "complete_local_task" -> AgentTaskWriteTools.completeTask(app, args["taskId"].orEmpty(), parseBool(args["completed"]) == true)
            "delete_local_task" -> AgentTaskWriteTools.deleteTask(app, args["taskId"].orEmpty())
            "set_contact_nickname" -> AgentMessagingChatTools.setNickname(app, args["userId"].orEmpty(), args["nickname"].orEmpty())
            "delete_local_message" -> AgentMessagingMessageTools.deleteLocalMessage(app, args["messageId"].orEmpty())
            "mark_notification_read" -> AgentMessagingChatTools.markNotificationRead(app, args["itemId"].orEmpty())
            "list_pinned_messages" -> AgentMessagingMessageReads.listPinned(app, args["chatId"].orEmpty())
            "list_friend_requests" -> AgentSocialReadTools.listFriendRequests(app, args["direction"].orEmpty())
            "list_friends" -> AgentSocialReadTools.listFriends(app)
            "search_users" -> AgentSocialReadTools.searchUsers(app, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 20)
            "list_posts" -> AgentSocialReadTools.listPosts(app, args["limit"]?.toIntOrNull() ?: 20)
            "get_post" -> AgentSocialReadTools.getPost(app, args["postId"].orEmpty())
            "list_post_comments" -> AgentSocialReadTools.listPostComments(app, args["postId"].orEmpty(), args["limit"]?.toIntOrNull() ?: 30)
            "list_blocked_users" -> AgentSocialReadTools.listBlocked(app)
            "revoke_message" -> AgentMessagingMessageTools.revokeMessage(app, args["messageId"].orEmpty())
            "react_to_message" -> AgentMessagingMessageTools.react(app, args["messageId"].orEmpty(), args["emoji"].orEmpty())
            "pin_message" -> AgentMessagingMessageTools.pinMessage(app, args["chatId"].orEmpty(), args["messageId"].orEmpty())
            "send_friend_request" -> AgentFriendWriteTools.sendFriend(app, args["userId"].orEmpty(), args["message"].orEmpty())
            "accept_friend_request" -> AgentFriendWriteTools.acceptFriend(app, args["requestId"].orEmpty())
            "reject_friend_request" -> AgentFriendWriteTools.rejectFriend(app, args["requestId"].orEmpty())
            "cancel_friend_request" -> AgentFriendWriteTools.cancelFriend(app, args["requestId"].orEmpty())
            "remove_friend" -> AgentFriendWriteTools.removeFriend(app, args["userId"].orEmpty())
            "block_user" -> AgentFriendWriteTools.blockUser(app, args["userId"].orEmpty())
            "unblock_user" -> AgentFriendWriteTools.unblockUser(app, args["userId"].orEmpty())
            "create_text_post" -> AgentPostWriteTools.createPost(app, args["text"].orEmpty(), args["visibility"])
            "like_post" -> AgentPostWriteTools.likePost(app, args["postId"].orEmpty(), parseBool(args["liked"]))
            "comment_on_post" -> AgentPostWriteTools.commentPost(app, args["postId"].orEmpty(), args["text"].orEmpty())
            "delete_post" -> AgentPostWriteTools.deletePost(app, args["postId"].orEmpty())
            "create_direct_chat" -> AgentGroupWriteTools.createDirect(app, args["userId"].orEmpty())
            "create_group" -> AgentGroupWriteTools.createGroup(app, args["name"].orEmpty(), args["memberIds"].orEmpty())
            "rename_group" -> AgentGroupWriteTools.renameGroup(app, args["chatId"].orEmpty(), args["name"].orEmpty())
            "update_group_announcement" -> AgentGroupWriteTools.updateAnnouncement(app, args["chatId"].orEmpty(), args["announcement"].orEmpty())
            "add_group_members" -> AgentGroupWriteTools.addMembers(app, args["chatId"].orEmpty(), args["memberIds"].orEmpty())
            "remove_group_member" -> AgentGroupWriteTools.removeMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty())
            "mute_group_member" -> AgentGroupWriteTools.muteMember(app, args["chatId"].orEmpty(), args["memberId"].orEmpty(), args["mutedUntil"]?.toLongOrNull() ?: 0L)
            "delete_chat" -> AgentGroupWriteTools.deleteChat(app, args["chatId"].orEmpty())
            "rewrite_text" -> "Error: rewrite_text is handled by the engine, not the host"
            else -> "Error: unknown tool $name"
        }.take(AgentToolPolicy.MAX_TOOL_RESULT_CHARS)
    }

    fun preview(name: String, argumentsJson: String): String {
        val args = parseArgs(argumentsJson)
        return when (name) {
            "send_text_message" ->
                "发到 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(160)}"
            "create_local_task" ->
                "任务「${args["title"].orEmpty().take(80)}」→ ${args["chatId"].orEmpty().take(24)}"
            "update_chat" ->
                "改会话 ${args["chatId"].orEmpty().take(24)} pinned=${args["pinned"]} muted=${args["muted"]} archived=${args["archived"]} unread=${args["markedUnread"]}"
            "set_draft" ->
                "草稿 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(80).ifBlank { "(清空)" }}"
            "star_message" ->
                "星标 ${args["messageId"].orEmpty().take(24)} → ${args["starred"]}"
            "complete_local_task" ->
                "待办 ${args["taskId"].orEmpty().take(24)} completed=${args["completed"]}"
            "delete_local_task" ->
                "删除待办 ${args["taskId"].orEmpty().take(24)}"
            "set_contact_nickname" ->
                "备注 ${args["userId"].orEmpty().take(24)} → ${args["nickname"].orEmpty().take(40).ifBlank { "(清除)" }}"
            "delete_local_message" ->
                "本机删除消息 ${args["messageId"].orEmpty().take(24)}"
            "mark_notification_read" ->
                "通知已读 ${args["itemId"].orEmpty().take(24)}"
            "revoke_message" -> "撤回 ${args["messageId"].orEmpty().take(24)}"
            "react_to_message" -> "反应 ${args["emoji"].orEmpty()} → ${args["messageId"].orEmpty().take(24)}"
            "pin_message" -> "置顶消息 ${args["messageId"].orEmpty().take(24)}"
            "send_friend_request" -> "好友申请 ${args["userId"].orEmpty().take(24)}"
            "accept_friend_request" -> "同意好友 ${args["requestId"].orEmpty().take(24)}"
            "reject_friend_request" -> "拒绝好友 ${args["requestId"].orEmpty().take(24)}"
            "create_text_post" -> "发动态：${args["text"].orEmpty().take(80)}"
            "like_post" -> "赞动态 ${args["postId"].orEmpty().take(24)} liked=${args["liked"]}"
            "comment_on_post" -> "评论动态 ${args["postId"].orEmpty().take(24)}"
            "delete_post" -> "删除动态 ${args["postId"].orEmpty().take(24)}"
            "create_direct_chat" -> "开聊 ${args["userId"].orEmpty().take(24)}"
            "create_group" -> "建群 ${args["name"].orEmpty().take(40)}"
            "rename_group" -> "改群名 ${args["name"].orEmpty().take(40)}"
            "add_group_members" -> "拉人进群 ${args["memberIds"].orEmpty().take(80)}"
            "remove_group_member" -> "移出群 ${args["memberId"].orEmpty().take(24)}"
            "mute_group_member" -> "禁言 ${args["memberId"].orEmpty().take(24)}"
            "block_user" -> "拉黑 ${args["userId"].orEmpty().take(24)}"
            "unblock_user" -> "取消拉黑 ${args["userId"].orEmpty().take(24)}"
            "delete_chat" -> "删除会话 ${args["chatId"].orEmpty().take(24)}"
            else -> "$name ${argumentsJson.take(160)}"
        }
    }

    internal fun parseArgs(raw: String): Map<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key) ?: return@forEach
            out[key] = value.toString()
        }
        return out
    }

    internal fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    internal fun token(app: MaodouchatApp): String? =
        TokenManager.getInstance(app).getToken()?.takeIf { it.isNotBlank() }

    internal fun fail(error: Throwable): String = "Error: ${error.message ?: error.javaClass.simpleName}"

}
