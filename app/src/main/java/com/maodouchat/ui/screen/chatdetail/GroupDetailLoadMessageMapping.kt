package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.groups.GroupDetailLoadMessage
import com.maodouchat.domain.groups.GroupInviteBotMessage

/** domain 侧 GroupDetailLoadMessage 到 app 字符串资源的映射。 */
internal fun groupDetailLoadMessageResId(message: GroupDetailLoadMessage): Int = when (message) {
    GroupDetailLoadMessage.SESSION_EXPIRED -> R.string.error_session_expired
    GroupDetailLoadMessage.CHAT_GROUP -> R.string.chat_group
    GroupDetailLoadMessage.LOAD_FAILED -> R.string.group_detail_load_failed
    GroupDetailLoadMessage.INVITE_REFRESHED -> R.string.group_detail_invite_refreshed
    GroupDetailLoadMessage.INVITE_FAILED -> R.string.group_detail_invite_failed
}

/** domain 侧 GroupInviteBotMessage 到 app 字符串资源的映射。 */
internal fun groupInviteBotMessageResId(message: GroupInviteBotMessage): Int = when (message) {
    GroupInviteBotMessage.INVITED -> R.string.group_play_bot_invited
    GroupInviteBotMessage.INVITE_FAILED -> R.string.group_play_bot_invite_failed
}
