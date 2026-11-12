package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatExportText

/** domain 侧 ChatExportText 到 app 字符串资源的映射。 */
internal fun chatExportTextResId(key: ChatExportText): Int = when (key) {
    ChatExportText.EXPORT_DISABLED -> R.string.chat_export_disabled
    ChatExportText.SECRET_CHAT_EXPORT_BLOCKED -> R.string.secret_chat_export_blocked
    ChatExportText.SESSION_EXPIRED -> R.string.error_session_expired
    ChatExportText.EXPORT_EMPTY -> R.string.chat_export_empty
    ChatExportText.GROUP_NAME -> R.string.chat_group
    ChatExportText.EXPORT_SHARE_TITLE -> R.string.chat_export_share_title
    ChatExportText.EXPORT_DONE -> R.string.chat_export_done
    ChatExportText.EXPORT_FAILED -> R.string.chat_export_failed
}
