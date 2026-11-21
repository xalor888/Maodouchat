package com.maodouchat.data.repository

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.ai.agent.LocalAiStorePrefs
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.domain.messaging.AgentSessionCodec
import com.maodouchat.domain.messaging.LocalAiProviderJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 本地 AI 两坨加密 prefs JSON 的一次性导入迁移（`maodou_local_ai-<userId>` 的
 * providers_json / sessions_json 两键退役；KEY_ACTIVE 与 KEY_OVERLAY 是标量偏好，仍留 prefs）。
 * Room 为空且 prefs 有数据时搬运后删键；此后两键不再被读写。
 * 导入失败不抛（调用方回退到旧 prefs 读，下次再试），已搬运成功才删键。
 */
internal class LocalAiLegacyMigration(
    private val appContext: Context,
    private val database: AppDatabase,
) {
    fun migrateIfNeeded(userId: String) {
        migrateProviders(userId)
        migrateSessions(userId)
    }

    private fun migrateProviders(userId: String) {
        val dao = database.localAiProviderDao()
        if (roomCount { dao.providersForOwnerBlocking(userId).size } > 0) {
            removeLegacyKey(LocalAiStorePrefs.KEY_PROVIDERS)
            return
        }
        val prefs = LocalAiStorePrefs.prefs(appContext) ?: return
        val raw = prefs.getString(LocalAiStorePrefs.scoped(appContext, LocalAiStorePrefs.KEY_PROVIDERS), null)
            ?.takeIf { it.isNotBlank() } ?: return
        val providers = runCatching { LocalAiProviderJsonCodec.parseProviders(raw) }.getOrNull().orEmpty()
        if (providers.isNotEmpty()) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    dao.replaceAllBlocking(userId, providers.mapIndexed { index, provider -> provider.toEntity(userId, index) })
                }
            }.onFailure { return }
        }
        runCatching { prefs.edit { remove(LocalAiStorePrefs.scoped(appContext, LocalAiStorePrefs.KEY_PROVIDERS)) } }
    }

    private fun migrateSessions(userId: String) {
        val dao = database.agentSessionDao()
        if (roomCount { dao.sessionsForOwnerBlocking(userId).size } > 0) {
            removeLegacyKey(LocalAiStorePrefs.KEY_SESSIONS)
            return
        }
        val prefs = LocalAiStorePrefs.prefs(appContext) ?: return
        val raw = prefs.getString(LocalAiStorePrefs.scoped(appContext, LocalAiStorePrefs.KEY_SESSIONS), null)
            ?.takeIf { it.isNotBlank() } ?: return
        val sessions = runCatching { AgentSessionCodec.parseSessions(raw) }.getOrNull().orEmpty()
        if (sessions.isNotEmpty()) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    val recent = AgentSessionCodec.retainRecent(sessions)
                    dao.replaceAllBlocking(userId, recent.mapIndexed { index, session -> session.toEntity(userId, index) })
                }
            }.onFailure { return }
        }
        runCatching { prefs.edit { remove(LocalAiStorePrefs.scoped(appContext, LocalAiStorePrefs.KEY_SESSIONS)) } }
    }

    private fun roomCount(block: () -> Int): Int = runCatching {
        runBlocking(Dispatchers.IO) { block() }
    }.getOrDefault(0)

    private fun removeLegacyKey(key: String) {
        runCatching {
            LocalAiStorePrefs.prefs(appContext)?.edit {
                remove(LocalAiStorePrefs.scoped(appContext, key))
            }
        }
    }
}
