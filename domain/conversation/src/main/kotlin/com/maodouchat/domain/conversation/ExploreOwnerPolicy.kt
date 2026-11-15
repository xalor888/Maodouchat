package com.maodouchat.domain.conversation

// 当前登录用户判定：当前用户 id 与期望 id 相等、且期望 id 非空；
// 空期望 id 时即使双方都是空串也不算本人，避免登出态误判。
// currentUserId 可空（SessionSnapshot.userId 是 String?，登出态为 null）。
// 收敛动态编排器与评论控制器两处逐字相同的 isCurrentOwner；
// 会话取值留在调用方（CurrentSession），策略只收纯字符串判定。
object ExploreOwnerPolicy {

    fun isCurrentOwner(currentUserId: String?, expectedUserId: String): Boolean =
        currentUserId == expectedUserId && expectedUserId.isNotBlank()
}
