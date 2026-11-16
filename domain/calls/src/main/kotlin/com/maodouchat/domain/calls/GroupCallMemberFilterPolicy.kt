package com.maodouchat.domain.calls

import com.maodouchat.core.model.User

// 群通话选成员搜索：显示名或 userId 含查询词（忽略大小写）；
// 自 ChatDetailGroupCallMemberDialog 的 filteredCallCandidates 收敛。
object GroupCallMemberFilterPolicy {

    fun matches(user: User, query: String): Boolean =
        user.displayName.contains(query, ignoreCase = true) ||
            user.id.contains(query, ignoreCase = true)

    fun filter(candidates: List<User>, query: String): List<User> {
        val q = query.trim()
        if (q.isBlank()) return candidates
        return candidates.filter { matches(it, q) }
    }
}
