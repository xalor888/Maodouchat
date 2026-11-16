package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Flag

// 运行时开关里被产品侧硬性关闭的项：读恒为 false，写恒存 false。
object RuntimeFlagPolicy {
    val FORCED_OFF_KEYS: Set<String> = setOf("nearby_enabled", "chat_export_enabled")

    fun isForcedOff(flag: Flag): Boolean = flag.key in FORCED_OFF_KEYS
}
