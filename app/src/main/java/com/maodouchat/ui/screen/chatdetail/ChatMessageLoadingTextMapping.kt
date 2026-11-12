package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatMessageLoadingText

/** domain 侧 ChatMessageLoadingText 到 app 字符串资源的映射。 */
internal fun chatMessageLoadingTextResId(message: ChatMessageLoadingText): Int = when (message) {
    ChatMessageLoadingText.GROUP_NAME -> R.string.chat_group
    ChatMessageLoadingText.MEMBER_COUNT -> R.plurals.chat_members_count
    ChatMessageLoadingText.GROUP_MEMBERS_CHANGED -> R.string.chat_group_members_changed_key
    ChatMessageLoadingText.LOAD_FAILED_TITLE -> R.string.chat_load_failed_title
}
