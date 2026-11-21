package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.domain.messaging.LocalAiProvider
import com.maodouchat.domain.messaging.LocalAiProviderJsonCodec
import com.maodouchat.domain.messaging.LocalAiProviderSelectionPolicy

// provider 增删改查簇：用户自配模型端点的列表/当前选中/新增更新/删除/切换。
// 后端已由加密 prefs JSON 迁入 Room `local_ai_providers` 表（v41）：读写经
// LocalAiRoomAccess 桥到 Dispatchers.IO，同步 API 与调用方一律不变；
// KEY_ACTIVE 仍是标量偏好，留 prefs。Room 为空且旧 prefs 仍有数据时读路径回退
// 旧 prefs（导入失败不丢数据），写成功后删旧键。
internal object LocalAiProviderCrud {
    fun listProviders(context: Context): List<LocalAiProvider> {
        val ownerUserId = LocalAiRoomAccess.ownerUserId(context)
        if (ownerUserId.isBlank()) return emptyList()
        LocalAiRoomAccess.migrateIfNeeded(context, ownerUserId)
        val room = runCatching {
            LocalAiRoomAccess.onDb {
                LocalAiRoomAccess.database(context).localAiProviderDao()
                    .providersForOwnerBlocking(ownerUserId).map { it.toModel() }
            }
        }.getOrNull().orEmpty()
        if (room.isNotEmpty()) return room
        return legacyProviders(context)
    }

    fun activeProvider(context: Context): LocalAiProvider? {
        val activeId = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
        return LocalAiProviderSelectionPolicy.resolveActive(listProviders(context), activeId)
    }

    fun upsertProvider(context: Context, provider: LocalAiProvider): LocalAiProvider {
        val ownerUserId = LocalAiRoomAccess.ownerUserId(context)
        if (ownerUserId.isBlank()) return provider
        LocalAiRoomAccess.migrateIfNeeded(context, ownerUserId)
        val next = LocalAiProviderSelectionPolicy.upsert(listProviders(context), provider)
        if (!writeProviders(context, ownerUserId, next)) {
            writeLegacyProviders(context, next)
        }
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), provider.id)
        }
        return provider
    }

    fun deleteProvider(context: Context, id: String) {
        val ownerUserId = LocalAiRoomAccess.ownerUserId(context)
        if (ownerUserId.isBlank()) return
        LocalAiRoomAccess.migrateIfNeeded(context, ownerUserId)
        val next = LocalAiProviderSelectionPolicy.delete(listProviders(context), id)
        if (!writeProviders(context, ownerUserId, next)) {
            writeLegacyProviders(context, next)
        }
        val prefs = LocalAiStorePrefs.prefs(context) ?: return
        prefs.edit {
            val active = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
            if (active == id) {
                putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), LocalAiProviderSelectionPolicy.activeIdAfterDelete(next, id, active))
            }
        }
    }

    fun setActive(context: Context, id: String) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), id)
        }
    }

    private fun writeProviders(context: Context, ownerUserId: String, providers: List<LocalAiProvider>): Boolean =
        runCatching {
            LocalAiRoomAccess.onDb {
                LocalAiRoomAccess.database(context).localAiProviderDao()
                    .replaceAllBlocking(ownerUserId, providers.mapIndexed { index, p -> p.toEntity(ownerUserId, index) })
            }
            LocalAiStorePrefs.prefs(context)?.edit {
                remove(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS))
            }
            true
        }.getOrDefault(false)

    private fun legacyProviders(context: Context): List<LocalAiProvider> {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return emptyList()
        val raw = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), "[]").orEmpty()
        return LocalAiProviderJsonCodec.parseProviders(raw)
    }

    private fun writeLegacyProviders(context: Context, providers: List<LocalAiProvider>) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(
                LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS),
                LocalAiProviderJsonCodec.encodeProviders(providers)
            )
        }
    }
}
