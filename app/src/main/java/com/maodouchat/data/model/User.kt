package com.maodouchat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val email: String = "",
    val isOnline: Boolean = false,
    val status: String = "",
    val nickname: String? = null,
    val lastSeen: Long = 0
) {
    /** Prefer nickname, then non-blank name; fall back to id so stub / missing-profile users never blank the UI. */
    val displayName: String get() = nickname?.takeIf(String::isNotBlank)
        ?: name.takeIf(String::isNotBlank)
        ?: id
}
