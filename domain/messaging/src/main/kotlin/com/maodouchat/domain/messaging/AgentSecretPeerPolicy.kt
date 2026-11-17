package com.maodouchat.domain.messaging

// 密聊参与者 id 集合提取：逗号分隔的 participantIds 拆开、去空格、去空、去重；
// 密聊筛选仍在 app 侧（要读库），这里只做纯字符串规整。
object AgentSecretPeerPolicy {

    fun secretPeerIds(secretChatsParticipantIds: List<String>): Set<String> =
        secretChatsParticipantIds.asSequence()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
}
