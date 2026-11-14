package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.core.model.MissedCall
import com.maodouchat.domain.calls.missedCallRetentionCutoffMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MissedCallRepository(private val dao: MissedCallDao) {

    fun observeRecent(sinceMs: Long = missedCallRetentionCutoffMs()): Flow<List<MissedCall>> =
        dao.observeRecent(since = sinceMs).map { list -> list.map { it.toDomain() } }

    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    suspend fun insert(call: MissedCall) {
        dao.insert(call.toEntity())
        // Opportunistic GC so the card cannot grow forever across restarts.
        trimToRetention()
    }

    suspend fun markAllRead() = dao.markAllRead()

    suspend fun delete(callId: String) = dao.deleteById(callId)

    suspend fun clearAll() = dao.deleteAll()

    suspend fun trimOld(olderThanMillis: Long) = dao.deleteOlderThan(olderThanMillis)

    suspend fun trimToRetention(nowMs: Long = System.currentTimeMillis()) {
        trimOld(missedCallRetentionCutoffMs(nowMs))
    }
}
