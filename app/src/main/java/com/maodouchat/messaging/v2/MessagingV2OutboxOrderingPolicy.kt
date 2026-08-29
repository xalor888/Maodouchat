package com.maodouchat.messaging.v2

/** Mirrors the causal kinds enforced by the Room outbox selection query. */
internal object MessagingV2OutboxOrderingPolicy {
    fun requiresConversationOrder(kind: String): Boolean = kind in CAUSAL_KINDS
    fun mayBypassConversationOrder(kind: String): Boolean = kind in BYPASS_KINDS

    private val CAUSAL_KINDS = setOf("DATA", "EVENT")
    private val BYPASS_KINDS = setOf("SENDER_KEY", "KEY_REQUEST", "RECEIPT")
}
