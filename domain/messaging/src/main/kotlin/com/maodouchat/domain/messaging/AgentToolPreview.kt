package com.maodouchat.domain.messaging

// Agent 工具执行前预览文案：纯字符串组合，自 app 侧三簇 ToolHost 的
// preview 收敛；工具名在三簇间互不重名，合并为单 when 与原文等价。
object AgentToolPreview {

    fun preview(name: String, args: Map<String, String>): String? = when (name) {
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
        "send_friend_request" -> "好友申请 ${args["userId"].orEmpty().take(24)}"
        "accept_friend_request" -> "同意好友 ${args["requestId"].orEmpty().take(24)}"
        "reject_friend_request" -> "拒绝好友 ${args["requestId"].orEmpty().take(24)}"
        "create_text_post" -> "发动态：${args["text"].orEmpty().take(80)}"
        "like_post" -> "赞动态 ${args["postId"].orEmpty().take(24)} liked=${args["liked"]}"
        "comment_on_post" -> "评论动态 ${args["postId"].orEmpty().take(24)}"
        "delete_post" -> "删除动态 ${args["postId"].orEmpty().take(24)}"
        "block_user" -> "拉黑 ${args["userId"].orEmpty().take(24)}"
        "unblock_user" -> "取消拉黑 ${args["userId"].orEmpty().take(24)}"
        "create_local_task" ->
            "任务「${args["title"].orEmpty().take(80)}」→ ${args["chatId"].orEmpty().take(24)}"
        "complete_local_task" ->
            "待办 ${args["taskId"].orEmpty().take(24)} completed=${args["completed"]}"
        "delete_local_task" ->
            "删除待办 ${args["taskId"].orEmpty().take(24)}"
        else -> null
    }
}
