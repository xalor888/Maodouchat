package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.MissedCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MissedCallRepository(private val dao: MissedCallDao) {

    fun observeRecent(sinceMs: Long = retentionCutoffMs()): Flow<List<MissedCall>> =
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
        trimOld(nowMs - RETENTION_MS)
    }

    companion object {
        /** Keep missed-call rows for 28 days; list UI only observes this window. */
        const val RETENTION_MS: Long = 28L * 24L * 60L * 60L * 1000L

        fun retentionCutoffMs(nowMs: Long = System.currentTimeMillis()): Long =
            nowMs - RETENTION_MS
    }
}
