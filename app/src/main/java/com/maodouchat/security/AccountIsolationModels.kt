package com.maodouchat.security

enum class AccountSwitchAction {
    /** Same account (or first login with no prior owner) — keep local encrypted store. */
    KEEP_LOCAL_DATA,
    /** Different owner detected — must destroy encrypted DB + cancel workers. */
    PURGE_LOCAL_DATA
}
