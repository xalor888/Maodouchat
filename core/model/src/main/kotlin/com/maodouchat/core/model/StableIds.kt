package com.maodouchat.core.model

@JvmInline
value class AccountId(val value: String) {
    init { require(value.isNotBlank()) { "AccountId must not be blank" } }
}

@JvmInline
value class ConversationId(val value: String) {
    init { require(value.isNotBlank()) { "ConversationId must not be blank" } }
}

@JvmInline
value class MessageId(val value: String) {
    init { require(value.isNotBlank()) { "MessageId must not be blank" } }
}
