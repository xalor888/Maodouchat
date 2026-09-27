package com.maodouchat.security

import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities

/**
 * 会话的密聊/锁定能力查询入口（U02 延伸：自各 ui 调用点的 app 单例直连收口）。
 *
 * 现状：`secretConversationController.capabilities(chatId)` 被 ui 里 6+ 处直接调用
 * （MainActivity、AiTasksScreen、ChatDetailViewModel/Deps/SecretChat、GroupDetailViewModel）。
 * 本对象是它们的非 ui 入口——本轮先只迁 AiTasksScreen（其余随各自批次迁移），
 * 迁完的调用点即从 ui 直连持久层棘轮退出。
 */
object SecretChatCapabilities {

    /** 读该会话的能力（同步读本地状态；调用方负责线程语义）。 */
    fun forChat(chatId: String): ConversationPrivacyCapabilities =
        MaodouchatApp.instance.secretConversationController.capabilities(chatId)
}
