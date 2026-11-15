package com.maodouchat.domain.contacts

// 无头像时的首字母渐变头像纯派生：首字母与按名字哈希稳定的渐变序号。
// 自 Avatar.kt / GroupAvatar.kt 两处逐字相同的行内逻辑收敛；渐变色表仍在
// app 侧，调用方传渐变总数，domain 只算序号。
object AvatarDisplayPolicy {

    // 空名回 "?"；大写仍由调用方在 Text 里做，与原行为一致。
    fun initial(name: String): String =
        name.firstOrNull()?.toString() ?: "?"

    // 名字哈希对渐变总数取模；mod 已保证非负，负值分支是原样保留的防御。
    fun gradientIndex(name: String, gradientCount: Int): Int =
        (name.hashCode().mod(gradientCount)).let {
            if (it < 0) it + gradientCount else it
        }
}
