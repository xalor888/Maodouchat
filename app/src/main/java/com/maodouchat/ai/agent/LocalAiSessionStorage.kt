package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.domain.messaging.AgentSession
import com.maodouchat.domain.messaging.AgentSessionCodec

// 会话簇：最近会话列表的读写与 JSON 编解码。
// 后端已由加密 prefs JSON 迁入 Room `agent_sessions` 表（v41）：读写经
// LocalAiRoomAccess 桥到 Dispatchers.IO，同步 API 与调用方一律不变。
// Room 为空且旧 prefs 仍有数据时读路径回退旧 prefs（导入失败不丢数据），
// 写成功后删旧键；保留上限 12 条的规则不变。
internal object LocalAiSessionStorage {
    fun loadSessions(context: Context): List<AgentSession> {
        val ownerUserId = LocalAiRoomAccess.ownerUserId(context)
        if (ownerUserId.isBlank()) return emptyList()
        LocalAiRoomAccess.migrateIfNeeded(context, ownerUserId)
        val room = runCatching {
            LocalAiRoomAccess.onDb {
                LocalAiRoomAccess.database(context).agentSessionDao()
                    .sessionsForOwnerBlocking(ownerUserId).mapNotNull { it.toModel() }
            }
        }.getOrNull().orEmpty()
        if (room.isNotEmpty()) return room
        return legacySessions(context)
    }

    fun saveSessions(context: Context, sessions: List<AgentSession>) {
        val ownerUserId = LocalAiRoomAccess.ownerUserId(context)
        if (ownerUserId.isBlank()) return
        LocalAiRoomAccess.migrateIfNeeded(context, ownerUserId)
        val recent = AgentSessionCodec.retainRecent(sessions)
        if (!writeSessions(context, ownerUserId, recent)) {
            writeLegacySessions(context, recent)
        }
    }

    internal fun parseSessions(raw: String): List<AgentSession> =
        AgentSessionCodec.parseSessions(raw)

    internal fun encodeSessions(list: List<AgentSession>): String =
        AgentSessionCodec.encodeSessions(list)

    private fun writeSessions(context: Context, ownerUserId: String, sessions: List<AgentSession>): Boolean =
        runCatching {
            LocalAiRoomAccess.onDb {
                LocalAiRoomAccess.database(context).agentSessionDao()
                    .replaceAllBlocking(ownerUserId, sessions.mapIndexed { index, s -> s.toEntity(ownerUserId, index) })
            }
            LocalAiStorePrefs.prefs(context)?.edit {
                remove(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS))
            }
            true
        }.getOrDefault(false)

    private fun legacySessions(context: Context): List<AgentSession> {
        val raw = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS), "[]").orEmpty()
        return parseSessions(raw)
    }

    private fun writeLegacySessions(context: Context, sessions: List<AgentSession>) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(
                LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS),
                encodeSessions(AgentSessionCodec.retainRecent(sessions))
            )
        }
    }
}
