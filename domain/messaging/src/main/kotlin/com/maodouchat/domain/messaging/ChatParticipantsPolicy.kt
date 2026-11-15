package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User

// 会话成员排除当前用户：群通话选成员与候选计数共用的纯过滤；
// 收敛群通话选成员对话框与上层对话框 3 处逐字相同的过滤/计数。
object ChatParticipantsPolicy {

    fun excludingCurrentUser(
        participants: List<User>,
        currentUserId: String
    ): List<User> =
        participants.filter { it.id != currentUserId }
}
