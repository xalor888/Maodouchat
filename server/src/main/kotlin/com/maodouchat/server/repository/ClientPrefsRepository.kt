package com.maodouchat.server.repository

import com.maodouchat.server.db.ClientPrefs
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ClientPrefsDto
import com.maodouchat.server.model.ClientPrefsUpdateRequest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Lightweight multi-device client preference blob
 * (theme / language / chat appearance / list prefs / AI writing-style /
 * app-lock timeout / screen-secure). Non-secret UX settings only —
 * not credentials, biometrics, or E2EE material.
 */
class ClientPrefsRepository {

    fun get(userId: String): ClientPrefsDto = transaction {
        ClientPrefs.selectAll()
            .where { ClientPrefs.userId eq userId }
            .singleOrNull()
            ?.let { row ->
                ClientPrefsDto(
                    themeMode = row[ClientPrefs.themeMode],
                    themeStyle = row[ClientPrefs.themeStyle],
                    languageMode = row[ClientPrefs.languageMode],
                    chatWallpaper = row[ClientPrefs.chatWallpaper],
                    chatFontScale = row[ClientPrefs.chatFontScale],
                    linkPreviewEnabled = row[ClientPrefs.linkPreviewEnabled],
                    unreadPriorityEnabled = row[ClientPrefs.unreadPriorityEnabled],
                    writingStyleEnabled = row[ClientPrefs.writingStyleEnabled],
                    writingStylePreset = row[ClientPrefs.writingStylePreset],
                    writingStyleCustom = row[ClientPrefs.writingStyleCustom],
                    appLockTimeoutMinutes = row[ClientPrefs.appLockTimeoutMinutes],
                    screenSecureEnabled = row[ClientPrefs.screenSecureEnabled],
                    sensitiveGateEnabled = row[ClientPrefs.sensitiveGateEnabled],
                    updatedAt = row[ClientPrefs.updatedAt]
                )
            }
            ?: ClientPrefsDto()
    }

    fun update(userId: String, request: ClientPrefsUpdateRequest): ClientPrefsDto = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction ClientPrefsDto()
        val existing = ClientPrefs.selectAll()
            .where { ClientPrefs.userId eq userId }
            .forUpdate()
            .singleOrNull()
        val now = System.currentTimeMillis()
        val theme = request.themeMode?.let { normalizeTheme(it) }
            ?: existing?.get(ClientPrefs.themeMode)
            ?: "system"
        val themeStyle = request.themeStyle?.let { normalizeThemeStyle(it) }
            ?: existing?.get(ClientPrefs.themeStyle)
            ?: "maodou"
        val language = request.languageMode?.let { normalizeLanguage(it) }
            ?: existing?.get(ClientPrefs.languageMode)
            ?: "system"
        val wallpaper = request.chatWallpaper?.let { normalizeWallpaper(it) }
            ?: existing?.get(ClientPrefs.chatWallpaper)
            ?: "default"
        val font = request.chatFontScale?.let { normalizeFont(it) }
            ?: existing?.get(ClientPrefs.chatFontScale)
            ?: "normal"
        val linkPreview = request.linkPreviewEnabled
            ?: existing?.get(ClientPrefs.linkPreviewEnabled)
            ?: true
        val unreadPriority = request.unreadPriorityEnabled
            ?: existing?.get(ClientPrefs.unreadPriorityEnabled)
            ?: true
        val styleEnabled = request.writingStyleEnabled
            ?: existing?.get(ClientPrefs.writingStyleEnabled)
            ?: false
        val stylePreset = request.writingStylePreset?.let { normalizeWritingPreset(it) }
            ?: existing?.get(ClientPrefs.writingStylePreset)
            ?: "none"
        val styleCustom = request.writingStyleCustom?.let { normalizeWritingCustom(it) }
            ?: existing?.get(ClientPrefs.writingStyleCustom)
            ?: ""
        // When disabled, clear memorable style fields on server
        val effectivePreset = if (styleEnabled) stylePreset else "none"
        val effectiveCustom = if (styleEnabled) styleCustom else ""
        val lockTimeout = request.appLockTimeoutMinutes?.let { normalizeAppLockTimeout(it) }
            ?: existing?.get(ClientPrefs.appLockTimeoutMinutes)
            ?: 5L
        val screenSecure = request.screenSecureEnabled
            ?: existing?.get(ClientPrefs.screenSecureEnabled)
            ?: false
        val sensitiveGate = request.sensitiveGateEnabled
            ?: existing?.get(ClientPrefs.sensitiveGateEnabled)
            ?: true

        if (existing == null) {
            ClientPrefs.insert {
                it[ClientPrefs.userId] = userId
                it[ClientPrefs.themeMode] = theme
                it[ClientPrefs.themeStyle] = themeStyle
                it[ClientPrefs.languageMode] = language
                it[ClientPrefs.chatWallpaper] = wallpaper
                it[ClientPrefs.chatFontScale] = font
                it[ClientPrefs.linkPreviewEnabled] = linkPreview
                it[ClientPrefs.unreadPriorityEnabled] = unreadPriority
                it[ClientPrefs.writingStyleEnabled] = styleEnabled
                it[ClientPrefs.writingStylePreset] = effectivePreset
                it[ClientPrefs.writingStyleCustom] = effectiveCustom
                it[ClientPrefs.appLockTimeoutMinutes] = lockTimeout
                it[ClientPrefs.screenSecureEnabled] = screenSecure
                it[ClientPrefs.sensitiveGateEnabled] = sensitiveGate
                it[ClientPrefs.updatedAt] = now
            }
        } else {
            ClientPrefs.update({ ClientPrefs.userId eq userId }) {
                it[ClientPrefs.themeMode] = theme
                it[ClientPrefs.themeStyle] = themeStyle
                it[ClientPrefs.languageMode] = language
                it[ClientPrefs.chatWallpaper] = wallpaper
                it[ClientPrefs.chatFontScale] = font
                it[ClientPrefs.linkPreviewEnabled] = linkPreview
                it[ClientPrefs.unreadPriorityEnabled] = unreadPriority
                it[ClientPrefs.writingStyleEnabled] = styleEnabled
                it[ClientPrefs.writingStylePreset] = effectivePreset
                it[ClientPrefs.writingStyleCustom] = effectiveCustom
                it[ClientPrefs.appLockTimeoutMinutes] = lockTimeout
                it[ClientPrefs.screenSecureEnabled] = screenSecure
                it[ClientPrefs.sensitiveGateEnabled] = sensitiveGate
                it[ClientPrefs.updatedAt] = now
            }
        }
        ClientPrefsDto(
            themeMode = theme,
            themeStyle = themeStyle,
            languageMode = language,
            chatWallpaper = wallpaper,
            chatFontScale = font,
            linkPreviewEnabled = linkPreview,
            unreadPriorityEnabled = unreadPriority,
            writingStyleEnabled = styleEnabled,
            writingStylePreset = effectivePreset,
            writingStyleCustom = effectiveCustom,
            appLockTimeoutMinutes = lockTimeout,
            screenSecureEnabled = screenSecure,
            sensitiveGateEnabled = sensitiveGate,
            updatedAt = now
        )
    }

    private fun normalizeTheme(raw: String): String = when (raw.trim().lowercase()) {
        "light" -> "light"
        "dark" -> "dark"
        else -> "system"
    }

    private fun normalizeThemeStyle(raw: String): String {
        val id = raw.trim().lowercase().take(24)
        return if (id in ALLOWED_THEME_STYLES) id else "maodou"
    }

    private fun normalizeLanguage(raw: String): String = when (raw.trim().lowercase()) {
        "zh", "zh-cn", "chinese" -> "zh"
        "en", "english" -> "en"
        else -> "system"
    }

    private fun normalizeWallpaper(raw: String): String {
        val id = raw.trim().lowercase().take(32)
        return if (id in ALLOWED_WALLPAPERS) id else "default"
    }

    private fun normalizeFont(raw: String): String {
        val id = raw.trim().lowercase().take(16)
        return if (id in ALLOWED_FONTS) id else "normal"
    }

    private fun normalizeWritingPreset(raw: String): String {
        val id = raw.trim().lowercase().take(40)
        return if (id in ALLOWED_WRITING_PRESETS) id else "none"
    }

    private fun normalizeWritingCustom(raw: String): String =
        raw.trim().replace(WHITESPACE_REGEX, " ").take(320)

    private fun normalizeAppLockTimeout(raw: Long): Long =
        if (raw in ALLOWED_APP_LOCK_TIMEOUTS) raw else 5L

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val ALLOWED_WALLPAPERS = setOf(
            "default", "mint", "lavender", "sand", "night", "rose", "sky", "slate", "peach", "olive", "coral", "plum",
            "indigo", "amber", "teal", "graphite"
        )
        private val ALLOWED_FONTS = setOf("small", "normal", "large", "xlarge", "xxlarge")
        private val ALLOWED_THEME_STYLES = setOf("maodou", "tg_classic", "tg_midnight", "tg_graphite")
        private val ALLOWED_WRITING_PRESETS = setOf(
            "none", "concise", "formal", "warm", "professional", "casual", "witty", "empathetic",
            "direct", "enthusiastic"
        )
        private val ALLOWED_APP_LOCK_TIMEOUTS = setOf(1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L)
    }
}
