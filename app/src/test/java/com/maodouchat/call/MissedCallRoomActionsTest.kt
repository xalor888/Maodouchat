package com.maodouchat.call

import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.entity.MissedCallEntity
import com.maodouchat.data.repository.MissedCallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallRoomActionsTest {

    private class FakeDao : MissedCallDao {
        val inserted = mutableListOf<MissedCallEntity>()
        var deleteAllCalls = 0
        val deletedIds = mutableListOf<String>()
        var throwOnDeleteAll = false
        var throwOnDeleteById = false

        override suspend fun insert(call: MissedCallEntity) {
            inserted += call
        }

        override fun observeRecent(since: Long): Flow<List<MissedCallEntity>> = flowOf(emptyList())

        override fun observeUnreadCount(): Flow<Int> = flowOf(0)

        override suspend fun markAllRead() = Unit

        override suspend fun deleteOlderThan(olderThan: Long) = Unit

        override suspend fun deleteById(callId: String) {
            if (throwOnDeleteById) throw RuntimeException("boom")
            deletedIds += callId
        }

        override suspend fun deleteAll() {
            if (throwOnDeleteAll) throw RuntimeException("boom")
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

    @Test
    fun clearAllTwiceHitsTheDaoTwiceWithoutDedup() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking {
            MissedCallRoomActions.clearAll()
            MissedCallRoomActions.clearAll()
        }
        assertEquals(2, dao.deleteAllCalls)
    }

    @Test
    fun deletePassesBlankIdsThroughVerbatim() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking { MissedCallRoomActions.delete("") }
        assertEquals(listOf(""), dao.deletedIds)
        assertEquals(0, dao.deleteAllCalls)
    }

    @Test
    fun clearAllAndDeleteShareTheSingleFactoryProvidedRepository() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking {
            MissedCallRoomActions.clearAll()
            MissedCallRoomActions.delete("call-9")
        }
        assertEquals(1, dao.deleteAllCalls)
        assertEquals(listOf("call-9"), dao.deletedIds)
    }

    @Test
    fun factoryIsEvaluatedPerCall() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking { MissedCallRoomActions.clearAll() }
        MissedCallRoomActions.repositoryFactory = { null }
        runBlocking { MissedCallRoomActions.delete("call-9") }
        assertEquals(1, dao.deleteAllCalls)
        assertTrue(dao.deletedIds.isEmpty())
    }

    @Test
    fun daoErrorsPropagateToTheCaller() {
        dao.throwOnDeleteAll = true
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        assertThrows(RuntimeException::class.java) {
            runBlocking { MissedCallRoomActions.clearAll() }
        }
    }

    @Test
    fun deleteErrorsPropagateToTheCaller() {
        dao.throwOnDeleteById = true
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        assertThrows(RuntimeException::class.java) {
            runBlocking { MissedCallRoomActions.delete("call-9") }
        }
        assertTrue(dao.deletedIds.isEmpty())
        assertEquals(0, dao.deleteAllCalls)
    }

    @Test
    fun deleteTwiceHitsTheDaoTwiceWithoutDedup() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking {
            MissedCallRoomActions.delete("call-9")
            MissedCallRoomActions.delete("call-9")
        }
        assertEquals(listOf("call-9", "call-9"), dao.deletedIds)
    }

    @Test
    fun throwingFactoryPropagatesToTheCaller() {
        MissedCallRoomActions.repositoryFactory = { throw RuntimeException("no app") }
        assertThrows(RuntimeException::class.java) {
            runBlocking { MissedCallRoomActions.clearAll() }
        }
    }

    @Test
    fun nullFactoryIsARepeatableSilentNoOp() {
        MissedCallRoomActions.repositoryFactory = { null }
        runBlocking {
            MissedCallRoomActions.clearAll()
            MissedCallRoomActions.delete("call-9")
            MissedCallRoomActions.clearAll()
        }
        assertEquals(0, dao.deleteAllCalls)
        assertTrue(dao.deletedIds.isEmpty())
    }

    @Test
    fun deletePassesWhitespaceIdThroughVerbatim() {
        MissedCallRoomActions.repositoryFactory = { MissedCallRepository(dao) }
        runBlocking { MissedCallRoomActions.delete("  ") }
        assertEquals(listOf("  "), dao.deletedIds)
        assertEquals(0, dao.deleteAllCalls)
    }
}
