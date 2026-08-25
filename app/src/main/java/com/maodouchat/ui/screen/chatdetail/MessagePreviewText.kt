package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatListPreviewPolicy

/**
 * 引用条 / 气泡引用 / 星标预览：永远不要把 Signal 信封 JSON 渲成正文。
 */
object MessagePreviewText {

    fun replyOrQuote(
        message: Message,
        mediaLabel: (MessageType) -> String,
        encryptedPlaceholder: String,
    ): String {
        val labeled = when (message.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE,
            MessageType.LOCATION -> mediaLabel(message.type)
            MessageType.REVOKED -> encryptedPlaceholder
            MessageType.SK_DIST -> encryptedPlaceholder
            else -> ChatListPreviewPolicy.redactedIfWire(
                message.parsedContent(),
                encryptedPlaceholder,
            )
        }
        return labeled.replace('\n', ' ').trim().ifBlank { encryptedPlaceholder }
    }
}
