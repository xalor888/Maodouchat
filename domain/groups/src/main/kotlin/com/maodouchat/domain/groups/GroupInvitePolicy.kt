package com.maodouchat.domain.groups

/** 群邀请链接的默认配置：7 天有效、最多 100 次使用。网关、控制器与 app 侧共用同一份默认值。 */
object GroupInvitePolicy {
    const val DEFAULT_EXPIRY_SECONDS: Long = 7L * 24L * 60L * 60L
    const val DEFAULT_MAX_USES: Int = 100

    // 邀请链接有效期选项（秒）：1 天 / 3 天 / 7 天 / 30 天。
    val EXPIRY_OPTIONS: List<Long> = listOf(
        24L * 60L * 60L,
        3L * 24L * 60L * 60L,
        7L * 24L * 60L * 60L,
        30L * 24L * 60L * 60L,
    )

    // 邀请链接最大使用次数选项。
    val MAX_USES_OPTIONS: List<Int> = listOf(1, 5, 10, 50, 100, 200, 500, 1000)
}
