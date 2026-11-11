package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatMessagePreviewText

/** domain 侧 ChatMessagePreviewText 到 app 字符串资源的映射。 */
internal fun chatMessagePreviewTextResId(message: ChatMessagePreviewText): Int = when (message) {
    ChatMessagePreviewText.NUDGE_YOU_NUDGED -> R.string.chat_nudge_you_nudged
    ChatMessagePreviewText.NUDGE_THEY_NUDGED_YOU -> R.string.chat_nudge_they_nudged_you
    ChatMessagePreviewText.NUDGE_THEY_NUDGED_TARGET -> R.string.chat_nudge_they_nudged_target
    ChatMessagePreviewText.PREVIEW_ENCRYPTED -> R.string.message_preview_encrypted
    ChatMessagePreviewText.PREVIEW_IMAGE -> R.string.message_preview_image
    ChatMessagePreviewText.PREVIEW_GIF -> R.string.message_preview_gif
    ChatMessagePreviewText.PREVIEW_STICKER -> R.string.message_preview_sticker
    ChatMessagePreviewText.PREVIEW_LOCATION -> R.string.message_preview_location
    ChatMessagePreviewText.PREVIEW_VOICE -> R.string.message_preview_voice
    ChatMessagePreviewText.PREVIEW_VIDEO -> R.string.message_preview_video
    ChatMessagePreviewText.PREVIEW_FILE -> R.string.message_preview_file
    ChatMessagePreviewText.MESSAGE_REVOKED_PLACEHOLDER -> R.string.chat_message_revoked_placeholder
}
