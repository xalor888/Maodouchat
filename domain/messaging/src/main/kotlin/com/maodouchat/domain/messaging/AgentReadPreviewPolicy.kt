package com.maodouchat.domain.messaging

// Agent 读操作结果行的预览截断，自 app 侧 ai/agent 读簇收敛；
// 行数上限与查询仍留在 app 侧，只迁正文截断本身。
object AgentReadPreviewPolicy {

    const val MAX_POST_LIST_CONTENT_CHARS: Int = 120
    const val MAX_POST_DETAIL_CONTENT_CHARS: Int = 1_000
    const val MAX_COMMENT_CONTENT_CHARS: Int = 160
    const val MAX_NOTIFICATION_PREVIEW_CHARS: Int = 80
    const val MAX_CONTACT_STATUS_CHARS: Int = 40
    const val MAX_FRIEND_REQUEST_MESSAGE_CHARS: Int = 80
    const val MAX_CHAT_LAST_MESSAGE_LIST_CHARS: Int = 80
    const val MAX_CHAT_LAST_MESSAGE_DETAIL_CHARS: Int = 160
    const val MAX_SEARCH_HIT_TEXT_CHARS: Int = 160

    fun postListContent(content: String): String = content.take(MAX_POST_LIST_CONTENT_CHARS)

    fun postDetailContent(content: String): String = content.take(MAX_POST_DETAIL_CONTENT_CHARS)

    fun commentContent(content: String): String = content.take(MAX_COMMENT_CONTENT_CHARS)

    fun notificationPreview(preview: String): String = preview.take(MAX_NOTIFICATION_PREVIEW_CHARS)

    fun contactStatus(status: String): String = status.take(MAX_CONTACT_STATUS_CHARS)

    fun friendRequestMessage(message: String): String = message.take(MAX_FRIEND_REQUEST_MESSAGE_CHARS)

    fun chatLastMessageList(lastMessage: String): String = lastMessage.take(MAX_CHAT_LAST_MESSAGE_LIST_CHARS)

    fun chatLastMessageDetail(lastMessage: String): String = lastMessage.take(MAX_CHAT_LAST_MESSAGE_DETAIL_CHARS)

    fun searchHitText(searchableText: String): String = searchableText.take(MAX_SEARCH_HIT_TEXT_CHARS)
}
