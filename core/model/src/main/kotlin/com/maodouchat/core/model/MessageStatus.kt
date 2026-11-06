package com.maodouchat.core.model

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED;

    /** Delivery ladder rank; FAILED is a terminal local side-channel and never upgrades/downgrades the ladder. */
    fun deliveryRank(): Int = when (this) {
        SENDING -> 0
        SENT -> 1
        DELIVERED -> 2
        READ -> 3
        FAILED -> -1
    }

    /** True when [next] is a valid advance (or same) on the delivery ladder. FAILED only replaces SENDING. */
    fun canAdvanceTo(next: MessageStatus): Boolean {
        if (this == next) return true
        if (next == FAILED) return this == SENDING
        // 8.45：FAILED 允许被服务端权威帧（SENT/DELIVERED/READ）覆盖——失败只是本地
        // side-channel；若消息实际已送达（服务端状态流可能跳过 SENT 直接发 DELIVERED），
        // 应恢复为真实送达状态而非永远显示失败。
        if (this == FAILED) return next == SENDING || next == SENT || next == DELIVERED || next == READ
        return next.deliveryRank() > deliveryRank()
    }

    companion object {
        /**
         * Unknown/missing wire values default conservatively to SENT.
         * Groups never fan out DELIVERED/READ as "everyone got it"; defaulting to DELIVERED
         * painted a double-check on messages nobody had received.
         */
        fun fromWire(value: String?): MessageStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SENT
    }
}
