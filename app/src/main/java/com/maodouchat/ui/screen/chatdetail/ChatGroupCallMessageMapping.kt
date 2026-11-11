package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.conversation.ChatGroupCallMessage

/** domain 侧 ChatGroupCallMessage 到 app 字符串资源的映射。 */
internal fun chatGroupCallMessageResId(message: ChatGroupCallMessage): Int = when (message) {
    ChatGroupCallMessage.CALLS_DISABLED -> R.string.calls_disabled
    ChatGroupCallMessage.VIDEO_CALL_DISABLED -> R.string.video_call_disabled
    ChatGroupCallMessage.VOICE_CALL_DISABLED -> R.string.voice_call_disabled
    ChatGroupCallMessage.GROUP_CALL_EMPTY -> R.string.chat_group_call_empty
}
