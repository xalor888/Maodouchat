package com.maodouchat.core.model

object MessagingV2EventAction {
    const val EDIT = "EDIT"
    const val REVOKE = "REVOKE"
    const val DELETE = "DELETE"
    const val REACTION_SET = "REACTION_SET"
    /** Legacy compatibility only. Receivers may merge only the envelope sender's entry. */
    const val REACTION_SNAPSHOT = "REACTION_SNAPSHOT"
    const val DELIVERY_RECEIPT = "DELIVERY_RECEIPT"
    const val READ_RECEIPT = "READ_RECEIPT"
    const val PLAY_RECEIPT = "PLAY_RECEIPT"
}
