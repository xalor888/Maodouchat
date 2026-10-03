package com.maodouchat.security

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities

object SecretChatCapabilities {

    /** 读该会话的能力（同步读本地状态；调用方负责线程语义）。 */
    fun forChat(chatId: String): ConversationPrivacyCapabilities =
        MaodouchatApp.instance.secretConversationController.capabilities(chatId)


    fun forChatOrNull(chatId: String): ConversationPrivacyCapabilities? =
        runCatching { MaodouchatApp.instance }.getOrNull()
            ?.secretConversationController
            ?.capabilities(chatId)
}
