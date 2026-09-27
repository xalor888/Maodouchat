package com.maodouchat.call

import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.entity.MissedCallEntity
import com.maodouchat.data.repository.MissedCallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U02 延伸：`MissedCallRoomActions` 的委派测试（JVM，假 DAO）。
 *
 * 钉住两件事：
 * 1. `clearAll`/`delete` 必须落到对应 DAO 动作（deleteAll / deleteById）；
 * 2. app 不可用（factory 返回 null，即原 `as? MaodouchatApp` 空判等价情形）→ **静默 no-op**
 *    而不是抛错（通话记录页点了「清空」不该因为环境问题崩）。
 */
class MissedCallRoomActionsTest {

    private class FakeDao : MissedCallDao {
        val inserted = mutableListOf<MissedCallEntity>()
        var deleteAllCalls = 0
        val deletedIds = mutableListOf<String>()

        override suspend fun insert(call: MissedCallEntity) {
            inserted += call
        }

        override fun observeRecent(since: Long): Flow<List<MissedCallEntity>> = flowOf(emptyList())

        override fun observeUnreadCount(): Flow<Int> = flowOf(0)

        override suspend fun markAllRead() = Unit

        override suspend fun deleteOlderThan(olderThan: Long) = Unit

        override suspend fun deleteById(callId: String) {
            deletedIds += callId
        }

        override suspend fun deleteAll() {
            deleteAllCalls += 1
        }
    }

    private val dao = FakeDao()

    @After
    fun tearDown() {
        // 恢复生产默认工厂（避免污染同 JVM 里其它用例）。
        MissedCallRoomActions.repositoryFactory = {
            runCatching {
                MissedCallRepository(com.maodouchat.MaodouchatApp.instance.database.missedCallDao())
            }.getOrNull()
        }
    }

    @Test
    fun clearAllDelegatesToDaoDeleteAll() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking { MissedCallRoomActions.clearAll() }
        assertEquals(1, dao.deleteAllCalls)
        assertTrue(dao.deletedIds.isEmpty())
    }

    @Test
    fun deleteDelegatesToDaoDeleteById() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking { MissedCallRoomActions.delete("call-9") }
        assertEquals(listOf("call-9"), dao.deletedIds)
        assertEquals(0, dao.deleteAllCalls)
    }

    @Test
    fun missingAppIsSilentNoOp() {
        MissedCallRoomActions.repositoryFactory = { null }
        runBlocking {
            MissedCallRoomActions.clearAll()
            MissedCallRoomActions.delete("call-9")
        }
        assertEquals(0, dao.deleteAllCalls)
        assertTrue(dao.deletedIds.isEmpty())
    }
}
