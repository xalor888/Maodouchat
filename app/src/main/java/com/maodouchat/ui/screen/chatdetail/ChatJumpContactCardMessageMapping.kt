package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.conversation.ChatContactCardMessage
import com.maodouchat.domain.conversation.ChatJumpMessage

/** domain 侧 ChatJumpMessage / ChatContactCardMessage 到 app 字符串资源的映射。 */
internal fun chatJumpMessageResId(message: ChatJumpMessage): Int = when (message) {
    ChatJumpMessage.DATE_EMPTY -> R.string.chat_jump_date_empty
}

internal fun chatContactCardMessageResId(message: ChatContactCardMessage): Int = when (message) {
    ChatContactCardMessage.CONTACT_CARD_DISABLED -> R.string.contact_card_disabled
    ChatContactCardMessage.CONTACT_CARD_SECRET_BLOCKED -> R.string.contact_card_secret_blocked
}
