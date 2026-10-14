package com.maodouchat.explore.policy

enum class PostVisibilityTier {
    PUBLIC,
    CONTACTS,
    PRIVATE;

    companion object {
        fun fromString(value: String?): PostVisibilityTier = when (value?.trim()?.uppercase()) {
            "PUBLIC" -> PUBLIC
            "CONTACTS", "FRIENDS" -> CONTACTS
            "PRIVATE" -> PRIVATE
            else -> PRIVATE // Fail-closed to PRIVATE
        }
    }
}

typealias PostVisibility = PostVisibilityTier
