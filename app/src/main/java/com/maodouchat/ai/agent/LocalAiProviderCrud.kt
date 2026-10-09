package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit

// provider 增删改查簇：用户自配模型端点的列表/当前选中/新增更新/删除/切换。
// 原 LocalAiProviderStorage 的 CRUD 部分整体搬入。
internal object LocalAiProviderCrud {
    fun listProviders(context: Context): List<LocalAiProvider> {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return emptyList()
        val raw = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), "[]").orEmpty()
        return LocalAiProviderJsonCodec.parseProviders(raw)
    }

    fun activeProvider(context: Context): LocalAiProvider? {
        val list = listProviders(context)
        if (list.isEmpty()) return null
        val activeId = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun upsertProvider(context: Context, provider: LocalAiProvider): LocalAiProvider {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return provider
        val next = listProviders(context).filterNot { it.id == provider.id } + provider
        prefs.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), LocalAiProviderJsonCodec.encodeProviders(next))
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), provider.id)
        }
        return provider
    }

    fun deleteProvider(context: Context, id: String) {
        val prefs = LocalAiStorePrefs.prefs(context) ?: return
        val next = listProviders(context).filterNot { it.id == id }
        prefs.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_PROVIDERS), LocalAiProviderJsonCodec.encodeProviders(next))
            val active = prefs.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), null)
            if (active == id) {
                putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), next.firstOrNull()?.id.orEmpty())
            }
        }
    }

    fun setActive(context: Context, id: String) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_ACTIVE), id)
        }
    }
}
