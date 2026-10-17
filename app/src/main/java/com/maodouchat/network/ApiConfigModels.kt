package com.maodouchat.network

sealed interface ServerChangeResult {
    data object Changed : ServerChangeResult
    data object Unchanged : ServerChangeResult
    data class Failed(val message: String) : ServerChangeResult
}

// Token 存储 Key
object Prefs {
    const val TOKEN_KEY = "auth_token"
    const val REFRESH_TOKEN_KEY = "refresh_token"
    const val ACCESS_TOKEN_EXPIRES_AT_KEY = "access_token_expires_at"
    const val REFRESH_TOKEN_EXPIRES_AT_KEY = "refresh_token_expires_at"
    const val USER_ID_KEY = "user_id"
    /** Survives logout token clear so same-account re-login can keep the SQLCipher + Signal store. */
    const val LAST_OWNER_USER_ID_KEY = "last_owner_user_id"
    const val PREFS_NAME = "maodouchat_prefs"
    const val LAST_SYNC_AT_KEY = "last_sync_at_ms"
}
