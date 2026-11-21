package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.messaging.v2.MessagingV2Outbox
import com.maodouchat.network.TokenManager
import com.maodouchat.security.DefaultSecretConversationController

// AI 工具执行所需的运行端口：工具只认这一组依赖，不再把整个 Application 线程进每个工具。
// 唯一组装点是 AgentToolHost.execute（仍从 MaodouchatApp.instance 取一次）；
// token/userId 在入口处解析一次，单次执行内与逐调用现取等价。
internal class AgentToolPorts(
    val database: AppDatabase,
    val messagingV2Outbox: MessagingV2Outbox,
    val secretConversationController: DefaultSecretConversationController,
    val notificationCenter: NotificationCenterRepository,
    val userId: String,
    val token: String?,
    val getString: (Int) -> String,
) {
    companion object {
        fun fromApp(app: MaodouchatApp): AgentToolPorts {
            val tokenManager = TokenManager.getInstance(app)
            return AgentToolPorts(
                database = app.database,
                messagingV2Outbox = app.messagingV2Outbox,
                secretConversationController = app.secretConversationController,
                notificationCenter = app.notificationCenter,
                userId = tokenManager.getUserId().orEmpty(),
                token = tokenManager.getToken()?.takeIf { it.isNotBlank() },
                getString = app::getString,
            )
        }
    }
}
