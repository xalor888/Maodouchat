package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User

// 聊天名解析：群名非空即用群名，否则取参与者 id 串里首个非本人 id，
// 再经查库回调取该人昵称（非空）或名字；三者皆空返回 ""。
// 收敛 AI 任务与媒体中心两处逐字相同的 resolveChatName 纯链路；
// 查库留在调用方（AppRepositories），策略只收纯字符串/查名逻辑；
// 查库回调是 suspend（UserRepository.getUserById 是挂起函数），
// 所以 resolve 也挂起——调用方本就在 suspend 的 resolveChatName 内。
object ChatNameResolvePolicy {

    suspend fun resolve(
        groupName: String?,
        participantIds: String,
        ownerUserId: String,
        peerName: suspend (String) -> String?
    ): String {
        groupName?.takeIf { it.isNotBlank() }?.let { return it }
        val peerId = participantIds.split(",").map { it.trim() }
            .filter { it.isNotBlank() && it != ownerUserId }
            .firstOrNull() ?: return ""
        return peerName(peerId) ?: ""
    }

    // 对端显示名：昵称非空即用昵称，否则原样用名字；与调用点逐字一致，
    // 刻意不用 User.displayName（后者会在名字也空白时回退到 id）。
    fun displayName(user: User): String =
        user.nickname?.takeIf { it.isNotBlank() } ?: user.name
}
