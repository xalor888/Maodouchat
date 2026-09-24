package com.maodouchat.explore.policy

/**
 * Account-scoped keys for Explore composer drafts so account A cannot reopen
 * account B's unsent post text after a soft account switch.
 */
object ExploreDraftPolicy {
    const val KEY_COMPOSER_TEXT = "composer_text"
    const val KEY_VISIBILITY = "selected_visibility"

    fun scopedKey(base: String, userId: String): String? {
        if (userId.isBlank()) return null
        return "$base:$userId"
    }

    /**
     * 回落 "PRIVATE"——G179：与设置页的回落方向相反（那里回落 "PUBLIC"）。
     * 这处分歧待决策，见 G179 台账条目。
     */
    fun normalizeVisibility(value: String): String =
        com.maodouchat.ui.screen.settings.normalizeVisibility(value, "PRIVATE")
}
