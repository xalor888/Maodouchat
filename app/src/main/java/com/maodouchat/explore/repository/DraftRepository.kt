package com.maodouchat.explore.repository

import android.content.SharedPreferences
import com.maodouchat.explore.policy.ExploreDraftPolicy

data class ExploreDraft(
    val composerText: String = "",
    val selectedVisibility: String = "PRIVATE",
    val imageUris: List<String> = emptyList()
)

interface DraftRepository {
    fun getDraft(ownerUserId: String, defaultVisibility: String? = null): ExploreDraft
    fun saveComposerText(ownerUserId: String, text: String)
    fun saveVisibility(ownerUserId: String, visibility: String)
    fun saveImageUris(ownerUserId: String, uris: List<String>)
    fun clearComposerDraft(ownerUserId: String)
    fun clearAllDrafts(ownerUserId: String)
}

class SharedPrefsDraftRepository(
    private val prefs: SharedPreferences
) : DraftRepository {

    companion object {
        const val KEY_DRAFT_IMAGES = "draft_images"
        private const val MIGRATION_KEY_COMPOSER = "composer_migrated_v1"
        private const val MIGRATION_KEY_VISIBILITY = "visibility_migrated_v1"
    }

    override fun getDraft(ownerUserId: String, defaultVisibility: String?): ExploreDraft {
        if (ownerUserId.isBlank()) {
            return ExploreDraft(
                composerText = "",
                selectedVisibility = ExploreDraftPolicy.normalizeVisibility(defaultVisibility ?: "PRIVATE"),
                imageUris = emptyList()
            )
        }

        val text = readComposerText(ownerUserId)
        val visibility = readVisibility(ownerUserId, defaultVisibility)
        val images = readImageUris(ownerUserId)

        return ExploreDraft(
            composerText = text,
            selectedVisibility = visibility,
            imageUris = images
        )
    }

    override fun saveComposerText(ownerUserId: String, text: String) {
        val key = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, ownerUserId) ?: return
        if (text.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, text).apply()
        }
    }

    override fun saveVisibility(ownerUserId: String, visibility: String) {
        val key = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, ownerUserId) ?: return
        val normalized = ExploreDraftPolicy.normalizeVisibility(visibility)
        prefs.edit().putString(key, normalized).apply()
    }

    override fun saveImageUris(ownerUserId: String, uris: List<String>) {
        val key = ExploreDraftPolicy.scopedKey(KEY_DRAFT_IMAGES, ownerUserId) ?: return
        if (uris.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, uris.joinToString("\n")).apply()
        }
    }

    override fun clearComposerDraft(ownerUserId: String) {
        val textKey = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, ownerUserId) ?: return
        val imagesKey = ExploreDraftPolicy.scopedKey(KEY_DRAFT_IMAGES, ownerUserId) ?: return
        prefs.edit()
            .remove(textKey)
            .remove(imagesKey)
            .apply()
    }

    override fun clearAllDrafts(ownerUserId: String) {
        val textKey = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, ownerUserId) ?: return
        val visKey = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, ownerUserId) ?: return
        val imagesKey = ExploreDraftPolicy.scopedKey(KEY_DRAFT_IMAGES, ownerUserId) ?: return
        prefs.edit()
            .remove(textKey)
            .remove(visKey)
            .remove(imagesKey)
            .apply()
    }

    private fun readComposerText(ownerUserId: String): String {
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, ownerUserId) ?: return ""
        prefs.getString(scoped, null)?.let { return it }

        // One-shot legacy migration for unscoped draft
        if (!prefs.getBoolean(MIGRATION_KEY_COMPOSER, false)) {
            val legacy = prefs.getString(ExploreDraftPolicy.KEY_COMPOSER_TEXT, null)
            if (!legacy.isNullOrEmpty()) {
                prefs.edit()
                    .putBoolean(MIGRATION_KEY_COMPOSER, true)
                    .putString(scoped, legacy)
                    .remove(ExploreDraftPolicy.KEY_COMPOSER_TEXT)
                    .apply()
                return legacy
            }
        }
        return ""
    }

    private fun readVisibility(ownerUserId: String, defaultVisibility: String?): String {
        val fallback = ExploreDraftPolicy.normalizeVisibility(defaultVisibility ?: "PRIVATE")
        val scoped = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, ownerUserId) ?: return fallback
        prefs.getString(scoped, null)?.let {
            return ExploreDraftPolicy.normalizeVisibility(it)
        }

        // One-shot legacy migration for unscoped draft
        if (!prefs.getBoolean(MIGRATION_KEY_VISIBILITY, false)) {
            val legacy = prefs.getString(ExploreDraftPolicy.KEY_VISIBILITY, null)
            if (!legacy.isNullOrEmpty()) {
                val normalized = ExploreDraftPolicy.normalizeVisibility(legacy)
                prefs.edit()
                    .putBoolean(MIGRATION_KEY_VISIBILITY, true)
                    .putString(scoped, normalized)
                    .remove(ExploreDraftPolicy.KEY_VISIBILITY)
                    .apply()
                return normalized
            }
        }
        return fallback
    }

    private fun readImageUris(ownerUserId: String): List<String> {
        val scoped = ExploreDraftPolicy.scopedKey(KEY_DRAFT_IMAGES, ownerUserId) ?: return emptyList()
        val raw = prefs.getString(scoped, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }
}
