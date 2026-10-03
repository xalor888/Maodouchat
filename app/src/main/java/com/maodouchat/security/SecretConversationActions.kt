package com.maodouchat.security

import com.maodouchat.MaodouchatApp

object SecretConversationActions {

    /** 开启「读后焚毁」（调用方保证会话确为密聊）。 */
    suspend fun armOnRead(conversationId: String) {
        MaodouchatApp.instance.secretConversationController.armOnRead(conversationId)
    }

    /** 清理已过期消息，返回被清理的 id 列表（原样转发）。 */
    suspend fun purgeExpiredMessages(nowMs: Long): List<String> =
        MaodouchatApp.instance.secretConversationController.purgeExpiredMessages(nowMs)
}
