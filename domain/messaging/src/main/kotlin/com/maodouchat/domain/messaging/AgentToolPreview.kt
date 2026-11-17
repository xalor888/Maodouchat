package com.maodouchat.domain.messaging

// Agent 工具执行前预览文案：纯字符串组合，自 app 侧三簇 ToolHost 的
// preview 收敛；工具名在三簇间互不重名，合并为单 when 与原文等价。
object AgentToolPreview {

    fun preview(name: String, args: Map<String, String>): String? = when (name) {
        AgentToolNamePolicy.SEND_TEXT_MESSAGE ->
            "发到 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(160)}"
        AgentToolNamePolicy.UPDATE_CHAT ->
            "改会话 ${args["chatId"].orEmpty().take(24)} pinned=${args["pinned"]} muted=${args["muted"]} archived=${args["archived"]} unread=${args["markedUnread"]}"
        AgentToolNamePolicy.SET_DRAFT ->
            "草稿 ${args["chatId"].orEmpty().take(24)}：${args["text"].orEmpty().take(80).ifBlank { "(清空)" }}"
        AgentToolNamePolicy.STAR_MESSAGE ->
            "星标 ${args["messageId"].orEmpty().take(24)} → ${args["starred"]}"
        AgentToolNamePolicy.SET_CONTACT_NICKNAME ->
            "备注 ${args["userId"].orEmpty().take(24)} → ${args["nickname"].orEmpty().take(40).ifBlank { "(清除)" }}"
        AgentToolNamePolicy.DELETE_LOCAL_MESSAGE ->
            "本机删除消息 ${args["messageId"].orEmpty().take(24)}"
        AgentToolNamePolicy.MARK_NOTIFICATION_READ ->
            "通知已读 ${args["itemId"].orEmpty().take(24)}"
        AgentToolNamePolicy.REVOKE_MESSAGE -> "撤回 ${args["messageId"].orEmpty().take(24)}"
        AgentToolNamePolicy.REACT_TO_MESSAGE -> "反应 ${args["emoji"].orEmpty()} → ${args["messageId"].orEmpty().take(24)}"
        AgentToolNamePolicy.PIN_MESSAGE -> "置顶消息 ${args["messageId"].orEmpty().take(24)}"
        AgentToolNamePolicy.CREATE_DIRECT_CHAT -> "开聊 ${args["userId"].orEmpty().take(24)}"
        AgentToolNamePolicy.CREATE_GROUP -> "建群 ${args["name"].orEmpty().take(40)}"
        AgentToolNamePolicy.RENAME_GROUP -> "改群名 ${args["name"].orEmpty().take(40)}"
        AgentToolNamePolicy.ADD_GROUP_MEMBERS -> "拉人进群 ${args["memberIds"].orEmpty().take(80)}"
        AgentToolNamePolicy.REMOVE_GROUP_MEMBER -> "移出群 ${args["memberId"].orEmpty().take(24)}"
        AgentToolNamePolicy.MUTE_GROUP_MEMBER -> "禁言 ${args["memberId"].orEmpty().take(24)}"
        AgentToolNamePolicy.DELETE_CHAT -> "删除会话 ${args["chatId"].orEmpty().take(24)}"
        AgentToolNamePolicy.SEND_FRIEND_REQUEST -> "好友申请 ${args["userId"].orEmpty().take(24)}"
        AgentToolNamePolicy.ACCEPT_FRIEND_REQUEST -> "同意好友 ${args["requestId"].orEmpty().take(24)}"
        AgentToolNamePolicy.REJECT_FRIEND_REQUEST -> "拒绝好友 ${args["requestId"].orEmpty().take(24)}"
        AgentToolNamePolicy.CREATE_TEXT_POST -> "发动态：${args["text"].orEmpty().take(80)}"
        AgentToolNamePolicy.LIKE_POST -> "赞动态 ${args["postId"].orEmpty().take(24)} liked=${args["liked"]}"
        AgentToolNamePolicy.COMMENT_ON_POST -> "评论动态 ${args["postId"].orEmpty().take(24)}"
        AgentToolNamePolicy.DELETE_POST -> "删除动态 ${args["postId"].orEmpty().take(24)}"
        AgentToolNamePolicy.BLOCK_USER -> "拉黑 ${args["userId"].orEmpty().take(24)}"
        AgentToolNamePolicy.UNBLOCK_USER -> "取消拉黑 ${args["userId"].orEmpty().take(24)}"
        AgentToolNamePolicy.CREATE_LOCAL_TASK ->
            "任务「${args["title"].orEmpty().take(80)}」→ ${args["chatId"].orEmpty().take(24)}"
        AgentToolNamePolicy.COMPLETE_LOCAL_TASK ->
            "待办 ${args["taskId"].orEmpty().take(24)} completed=${args["completed"]}"
        AgentToolNamePolicy.DELETE_LOCAL_TASK ->
            "删除待办 ${args["taskId"].orEmpty().take(24)}"
        else -> null
    }
}
