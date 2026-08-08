package com.maodouchat.util

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/**
 * View-once (open-once) media policy for 1:1 chats.
 * Flag travels inside E2EE MessageMeta so the server only sees ciphertext.
 */
object ViewOncePolicy {
    private val SUPPORTED = setOf(
        MessageType.IMAGE,
        MessageType.VIDEO,
        MessageType.GIF
    )

    fun supports(type: MessageType): Boolean = type in SUPPORTED

    private fun metaOf(message: Message) = message.parsedMeta()

    fun isViewOnce(message: Message): Boolean {
        val meta = metaOf(message)
        return meta.viewOnce && supports(message.type)
    }

    fun isLockedForViewer(message: Message, isOwnMessage: Boolean): Boolean {
        if (!isViewOnce(message)) return false
        if (isOwnMessage) return false
        return metaOf(message).viewOnceOpened
    }

    fun markOpened(message: Message): Message {
        if (!isViewOnce(message) || metaOf(message).viewOnceOpened) return message
        val meta = metaOf(message).copy(viewOnceOpened = true)
        return message.withEncodedMeta(meta)
    }
}
