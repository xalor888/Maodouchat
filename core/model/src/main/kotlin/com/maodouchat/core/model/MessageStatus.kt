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
