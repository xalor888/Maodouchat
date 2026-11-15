package com.maodouchat.domain.contacts

// 用户 ID 展示缩写：超长 ID 只显示前 8 + … + 末 4 位，短 ID 原样显示。
// 作者资料页与设置资料簇两处逐字相同的行内逻辑收敛；点按复制完整 ID 的行为仍在调用方。
object UserIdDisplayPolicy {

    const val MAX_FULL_LENGTH: Int = 16
    const val HEAD_LENGTH: Int = 8
    const val TAIL_LENGTH: Int = 4

    fun abbreviate(userId: String): String =
        if (userId.length > MAX_FULL_LENGTH) userId.take(HEAD_LENGTH) + "…" + userId.takeLast(TAIL_LENGTH) else userId
}
