package com.maodouchat.server.repository

import com.maodouchat.server.db.ClientPrefs
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ClientPrefsDto
import com.maodouchat.server.model.ClientPrefsUpdateRequest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 客户端偏好写入（归一化 + upsert）。 */
fun ClientPrefsRepository.update(userId: String, request: ClientPrefsUpdateRequest): ClientPrefsDto = transaction {
    val user = Users.selectAll()
        .where { Users.id eq userId }
        .forUpdate()
        .firstOrNull()
        ?: throw IllegalArgumentException("user not found")
    if (user[Users.deletedAt] != null) {
        throw IllegalArgumentException("user not found")
    }
    val existing = ClientPrefs.selectAll()
        .where { ClientPrefs.userId eq userId }
        .forUpdate()
        .singleOrNull()
    val now = System.currentTimeMillis()
    val theme = request.themeMode?.let { normalizeTheme(it) }
        ?: existing?.get(ClientPrefs.themeMode)
        ?: "system"
    val themeStyle = normalizeThemeStyle(
        request.themeStyle ?: existing?.get(ClientPrefs.themeStyle) ?: "maodou"
    )
    val accentColor = request.accentColor?.let { normalizeAccentColor(it) }
        ?: existing?.get(ClientPrefs.accentColor)
        ?: "none"
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
            it[ClientPrefs.accentColor] = accentColor
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
            it[ClientPrefs.accentColor] = accentColor
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
        accentColor = accentColor,
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

