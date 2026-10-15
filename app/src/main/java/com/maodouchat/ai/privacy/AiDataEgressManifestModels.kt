package com.maodouchat.ai.privacy

enum class EgressDecision {
    ALLOWED,
    DENIED_SECRET_CHAT,
    DENIED_LOCKED_CHAT,
    DENIED_CREDENTIAL_LEAK,
    DENIED_EMPTY_OR_CORRUPT
}
