package com.maodouchat.ui.screen.explore

/**
 * Account-scoped keys for Explore composer drafts so account A cannot reopen
 * account B's unsent post text after a soft account switch.
 */
object ExploreDraftPolicy {
    const val KEY_COMPOSER_TEXT = "composer_text"
    const val KEY_VISIBILITY = "selected_visibility"
    val VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")

    fun scopedKey(base: String, userId: String): String? {
        if (userId.isBlank()) return null
        return "$base:$userId"
    }

    fun normalizeVisibility(value: String): String =
        value.takeIf { it in VISIBILITIES } ?: "PRIVATE"
}
