package com.maodouchat.domain.messaging

// Agent 工具写操作入口的参数清洗，自 app 侧 ai/agent 写操作簇收敛；
// 网络/数据库调用仍留在 app 侧。
object AgentToolInputPolicy {

    const val MAX_TASK_TITLE_CHARS: Int = 300
    const val MAX_TASK_DUE_TEXT_CHARS: Int = 120
    const val MAX_POST_BODY_CHARS: Int = 2_000
    const val MAX_COMMENT_BODY_CHARS: Int = 800
    const val MAX_FRIEND_REQUEST_MESSAGE_CHARS: Int = 300
    const val MAX_ANNOUNCEMENT_CHARS: Int = 1_200
    const val MAX_REACTION_EMOJI_CHARS: Int = 16

    fun cleanTaskTitle(title: String): String = title.trim().take(MAX_TASK_TITLE_CHARS)

    fun cleanTaskDueText(dueText: String?): String? =
        dueText?.trim()?.take(MAX_TASK_DUE_TEXT_CHARS)?.takeIf { it.isNotBlank() }

    fun cleanPostBody(text: String): String = text.trim().take(MAX_POST_BODY_CHARS)

    fun cleanCommentBody(text: String): String = text.trim().take(MAX_COMMENT_BODY_CHARS)

    fun cleanFriendRequestMessage(message: String): String = message.take(MAX_FRIEND_REQUEST_MESSAGE_CHARS)

    fun cleanAnnouncement(announcement: String): String = announcement.take(MAX_ANNOUNCEMENT_CHARS)

    fun normalizeReactionEmoji(emoji: String): String = emoji.trim().take(MAX_REACTION_EMOJI_CHARS)
}
