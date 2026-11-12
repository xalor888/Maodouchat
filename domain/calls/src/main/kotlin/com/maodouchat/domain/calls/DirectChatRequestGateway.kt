package com.maodouchat.domain.calls

import com.maodouchat.core.model.ChatDto

/** 建单聊的网络端口。app 侧直通 ChatNetworkRepository。 */
interface DirectChatRequestGateway {
    suspend fun createChat(token: String, peerIds: List<String>): Result<ChatDto>
}

/** 建单聊时读取的会话快照端口。app 侧直通 CurrentSession / BackgroundSessionGate。 */
interface DirectChatRequestSession {
    fun ownerUserId(): String
    fun token(): String
    fun mayContinue(expectedUserId: String): Boolean
}
