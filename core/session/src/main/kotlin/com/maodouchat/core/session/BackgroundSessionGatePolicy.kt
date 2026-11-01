package com.maodouchat.core.session

object BackgroundSessionGatePolicy {

    // 令牌只验存在不比值：门禁防的是「换号/登出后旧任务继续跑」，不是令牌轮换。
    fun mayContinue(
        expectedUserId: String,
        liveToken: String?,
        liveUserId: String?,
        purgeInProgress: Boolean,
    ): Boolean {
        if (purgeInProgress) return false
        if (expectedUserId.isBlank()) return false
        if (liveToken.isNullOrBlank() || liveUserId.isNullOrBlank()) return false
        return liveUserId == expectedUserId
    }
}
