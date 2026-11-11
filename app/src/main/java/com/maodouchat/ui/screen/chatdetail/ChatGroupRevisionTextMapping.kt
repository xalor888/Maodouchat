package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatGroupRevisionText

/** domain 侧 ChatGroupRevisionText 到 app 字符串资源的映射。 */
internal fun chatGroupRevisionTextResId(message: ChatGroupRevisionText): Int = when (message) {
    ChatGroupRevisionText.LEFT_GROUP_KEY_CLEARED -> R.string.chat_left_group_key_cleared
    ChatGroupRevisionText.GROUP_MEMBERS_CHANGED_KEY -> R.string.chat_group_members_changed_key
    ChatGroupRevisionText.GROUP_NAME_UPDATED -> R.string.chat_group_name_updated
    ChatGroupRevisionText.GROUP_ROLE_UPDATED -> R.string.chat_group_role_updated
    ChatGroupRevisionText.GROUP_TITLE_UPDATED -> R.string.chat_group_title_updated
    ChatGroupRevisionText.GROUP_NICKNAME_UPDATED -> R.string.chat_group_nickname_updated
    ChatGroupRevisionText.GROUP_ANNOUNCEMENT_UPDATED -> R.string.chat_group_announcement_updated
    ChatGroupRevisionText.GROUP_MUTE_UPDATED -> R.string.chat_group_mute_updated
    ChatGroupRevisionText.GROUP_INFO_UPDATED -> R.string.chat_group_info_updated
}
