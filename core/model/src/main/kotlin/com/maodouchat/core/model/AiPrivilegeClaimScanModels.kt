package com.maodouchat.core.model

enum class PrivilegeClaimKind {
    TRANSFER_OR_PAYMENT,
    ACCOUNT_DESTRUCTIVE,
    AUTH_OR_KEY,
    ADMIN_OR_MODERATION,
    OTHER_PRIVILEGED
}

data class PrivilegeScan(
    val hasClaim: Boolean,
    val kinds: Set<PrivilegeClaimKind> = emptySet()
)
