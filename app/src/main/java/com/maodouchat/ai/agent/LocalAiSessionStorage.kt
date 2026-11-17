package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.AgentSession
import com.maodouchat.domain.messaging.AgentSessionCodec

// 会话簇：最近会话列表的读写与 JSON 编解码。原 LocalAiProviderStore 的会话部分整体搬入。
internal object LocalAiSessionStorage {
    fun loadSessions(context: Context): List<AgentSession> {
        val raw = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS), "[]").orEmpty()
        return parseSessions(raw)
    }

    fun saveSessions(context: Context, sessions: List<AgentSession>) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(
                LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS),
                encodeSessions(AgentSessionCodec.retainRecent(sessions))
            )
        }
    }

    internal fun parseSessions(raw: String): List<AgentSession> =
        AgentSessionCodec.parseSessions(raw)

    internal fun encodeSessions(list: List<AgentSession>): String =
        AgentSessionCodec.encodeSessions(list)
}
