package com.maodouchat.call

enum class Decision {
    /** 继续处理。 */
    ACCEPT,
    /** 静默丢弃。 */
    DROP,
    /** 对 offer 回 busy，其它类型丢弃。 */
    BUSY_REJECT,
}
