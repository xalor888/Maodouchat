package com.maodouchat.security

import com.maodouchat.MaodouchatApp

/**
 * 密聊会话的两个**命令**入口（U02 延伸：自 `ui/screen/chatdetail/ChatDetailDisappearing`
 * 的 app 单例直连收口）。
 *
 * 与 `SecretChatCapabilities` 的分工：那边是**读**（capabilities），这里是**写**
 * （armOnRead 开启「读后焚毁」/ purgeExpiredMessages 清理已过期消息）。两者都只是
 * `secretConversationController` 的非 ui 入口，语义逐字转发。
 */
object SecretConversationActions {

    /** 开启「读后焚毁」（调用方保证会话确为密聊）。 */
    suspend fun armOnRead(conversationId: String) {
        MaodouchatApp.instance.secretConversationController.armOnRead(conversationId)
    }

    /** 清理已过期消息，返回被清理的 id 列表（原样转发）。 */
    suspend fun purgeExpiredMessages(nowMs: Long): List<String> =
        MaodouchatApp.instance.secretConversationController.purgeExpiredMessages(nowMs)
}
