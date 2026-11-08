package com.maodouchat.conversation

import android.app.Application
import com.maodouchat.core.model.ConversationCreationPort
import com.maodouchat.domain.conversation.DefaultConversationCreationPort
import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession
import com.maodouchat.util.RuntimeFlags

/** app 侧装配：会话创建端口的网络调用与会话门禁，UI 层只拿成品端口。 */
internal fun createAndroidConversationCreationPort(
    application: Application,
): ConversationCreationPort = DefaultConversationCreationPort(
    sessionProvider = {
        val snap = CurrentSession.snapshot()
        Pair(snap.userId, snap.token)
    },
    createChatApi = { token, ids, isGroup, name, type ->
        ApiService.createChat(token, ids, isGroup, name, type)
    },
    isOwnerSessionCurrent = { ownerUserId, token ->
        BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
            liveToken = token,
            liveUserId = ownerUserId,
        )
    },
    isSecretChatFeatureEnabled = {
        RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_CHAT)
    },
)
