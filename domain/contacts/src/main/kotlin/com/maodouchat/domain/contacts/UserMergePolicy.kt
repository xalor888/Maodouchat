package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User

// 入站用户资料与本地已缓存资料合并：服务端下发的 UserDto 通常不含本地备注、
// 头像与在线态等字段，空值不覆盖本地已有值；收敛 ChatRepository.cacheChats
// 与 UserRepository.insertUsers 两处逐字相同的合并逻辑。
object UserMergePolicy {

    fun merge(incoming: User, existing: User?): User =
        incoming.copy(
            nickname = incoming.nickname?.takeIf { it.isNotBlank() } ?: existing?.nickname,
            avatar = incoming.avatar?.takeIf { it.isNotBlank() } ?: existing?.avatar,
            email = incoming.email.takeIf { it.isNotBlank() } ?: existing?.email ?: "",
            isOnline = if (incoming.isOnline) true else existing?.isOnline ?: false,
            status = incoming.status.takeIf { it.isNotBlank() } ?: existing?.status ?: "",
            lastSeen = if (incoming.lastSeen > 0) incoming.lastSeen else existing?.lastSeen ?: 0
        )
}
