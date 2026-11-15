package com.maodouchat.domain.groups

/** 群邀请链接的默认配置：7 天有效、最多 100 次使用。网关、控制器与 app 侧共用同一份默认值。 */
object GroupInvitePolicy {
    const val DEFAULT_EXPIRY_SECONDS: Long = 7L * 24L * 60L * 60L
    const val DEFAULT_MAX_USES: Int = 100
}
