package com.maodouchat.security

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.domain.conversation.SecretChatActivityHeartbeatPolicy

// B2 密聊活动心跳：纯循环已迁入 :domain:conversation，这里只留 Android 侧装配。
object SecretChatActivityHeartbeat {

    /** Android 侧装配：从 Application 取 dao 并接上 TTL 判定。 */
    suspend fun start(context: Context, chatId: String) {
        val app = context.applicationContext as? MaodouchatApp ?: return
        val dao = app.database.secretChatDao()
        SecretChatActivityHeartbeatPolicy.run(
            chatId = chatId,
            readLastActivityAt = { id -> dao.get(id)?.lastActivityAt },
            isExpired = { id, lastActivityAt -> SecretSessionTtl.isExpired(context, id, lastActivityAt) },
            destroy = { id -> SecretSessionTtl.destroySession(context, id) },
            touch = { id -> dao.touchActivity(id) },
        )
    }
}
