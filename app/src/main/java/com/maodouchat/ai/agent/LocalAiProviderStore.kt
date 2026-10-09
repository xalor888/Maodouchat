package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit

/**
 * Account-scoped encrypted store for user-owned model endpoints.
 * Keys never go to the Maodou chat server.
 *
 * 实现已按簇拆到 LocalAiProviderStorage / LocalAiSessionStorage / LocalAiStorePrefs，
 * 这里只保留统一入口（含测试引用的 internal 编解码委托）。
 */
object LocalAiProviderStore {
    fun listProviders(context: Context): List<LocalAiProvider> =
        LocalAiProviderStorage.listProviders(context)

    fun activeProvider(context: Context): LocalAiProvider? =
        LocalAiProviderStorage.activeProvider(context)

    fun upsertProvider(context: Context, provider: LocalAiProvider): LocalAiProvider =
        LocalAiProviderStorage.upsertProvider(context, provider)

    fun deleteProvider(context: Context, id: String) =
        LocalAiProviderStorage.deleteProvider(context, id)

    fun setActive(context: Context, id: String) =
        LocalAiProviderStorage.setActive(context, id)

    fun overlayMode(context: Context): AgentOverlayMode {
        val raw = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_OVERLAY), AgentOverlayMode.DISABLED.name)
        return runCatching { AgentOverlayMode.valueOf(raw.orEmpty()) }.getOrDefault(AgentOverlayMode.DISABLED)
    }

    fun setOverlayMode(context: Context, mode: AgentOverlayMode) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_OVERLAY), mode.name)
        }
    }

    fun loadSessions(context: Context): List<AgentSession> =
        LocalAiSessionStorage.loadSessions(context)

    fun saveSessions(context: Context, sessions: List<AgentSession>) =
        LocalAiSessionStorage.saveSessions(context, sessions)

    fun newProviderDraft(protocol: LocalAiProtocol = LocalAiProtocol.OPENAI_CHAT_COMPLETIONS): LocalAiProvider =
        LocalAiProviderStorage.newProviderDraft(protocol)

    fun isConfigured(context: Context): Boolean =
        LocalAiProviderStorage.isConfigured(context)

    internal fun parseProviders(raw: String): List<LocalAiProvider> =
        LocalAiProviderStorage.parseProviders(raw)

    internal fun encodeProviders(list: List<LocalAiProvider>): String =
        LocalAiProviderStorage.encodeProviders(list)

    internal fun parseSessions(raw: String): List<AgentSession> =
        LocalAiSessionStorage.parseSessions(raw)

    internal fun encodeSessions(list: List<AgentSession>): String =
        LocalAiSessionStorage.encodeSessions(list)
}
