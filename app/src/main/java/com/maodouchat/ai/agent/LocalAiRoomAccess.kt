package com.maodouchat.ai.agent

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.LocalAiLegacyMigration
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 本地 AI 的 Room 访问桥接：同步 API 不变，磁盘读写经 runBlocking(Dispatchers.IO)
 * 走后台线程（调用方横跨主线程与后台，直接换 suspend 会波及全部调用点，
 * 与 NotificationCenterRepository 的做法一致）。
 * 每个账号首次访问时跑一次旧 prefs 导入；导入失败不抛，下次访问重试。
 */
internal object LocalAiRoomAccess {
    private val lock = Any()
    private val migratedOwners = mutableSetOf<String>()

    fun <T> onDb(block: () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { block() }
    }

    fun database(context: Context): AppDatabase =
        AppDatabase.getInstance(context.applicationContext)

    fun ownerUserId(context: Context): String =
        TokenManager.getInstance(context).getUserId().orEmpty()

    fun migrateIfNeeded(context: Context, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        synchronized(lock) { if (!migratedOwners.add(ownerUserId)) return }
        runCatching {
            LocalAiLegacyMigration(context.applicationContext, database(context))
                .migrateIfNeeded(ownerUserId)
        }.onFailure {
            synchronized(lock) { migratedOwners.remove(ownerUserId) }
        }
    }
}
