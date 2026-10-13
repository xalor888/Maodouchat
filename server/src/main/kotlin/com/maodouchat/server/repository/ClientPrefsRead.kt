package com.maodouchat.server.repository

import com.maodouchat.server.db.ClientPrefs
import com.maodouchat.server.model.ClientPrefsDto
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 客户端偏好读取（云同步查询入口）。 */
fun ClientPrefsRepository.get(userId: String): ClientPrefsDto = transaction {
    ClientPrefs.selectAll()
        .where { ClientPrefs.userId eq userId }
        .singleOrNull()
        ?.let { row ->
            ClientPrefsDto(
                themeMode = row[ClientPrefs.themeMode],
                themeStyle = normalizeThemeStyle(row[ClientPrefs.themeStyle]),
                accentColor = row[ClientPrefs.accentColor],
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

