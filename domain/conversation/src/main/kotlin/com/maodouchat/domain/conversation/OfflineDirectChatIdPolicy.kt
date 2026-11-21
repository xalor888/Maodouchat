package com.maodouchat.domain.conversation

/** 离线直聊会话确定性 id：直聊 `direct_<owner>_<peer>`，密聊 `secret_<owner>_<peer>`。 */
object OfflineDirectChatIdPolicy {
    fun offlineDirectChatId(ownerUserId: String, peerUserId: String, isSecret: Boolean): String =
        if (isSecret) "secret_${ownerUserId}_${peerUserId}" else "direct_${ownerUserId}_${peerUserId}"
}
