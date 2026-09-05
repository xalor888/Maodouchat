package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B03 [PreKeyStore] 原子性测试（对照重构清单 B03 Gate：one-time pre-key
 * 消费必须使用数据库原子操作，并发消费恰好一人成功）。
 */
class PreKeyStoreTest {

    private var database: Database? = null

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `consume marks key consumed, second consume returns null`() {
        setupDb()
        val store = PreKeyStore()
        transaction { store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "key-7")) }

        val first = store.consumePreKey(USER_ID, DEVICE_ID)
        assertEquals(7, first?.keyId)
        assertEquals("key-7", first?.publicKeyBase64)

        assertNull(store.peekPreKey(USER_ID, DEVICE_ID))
        assertNull(store.consumePreKey(USER_ID, DEVICE_ID))
    }

    @Test
    fun `consume on empty mailbox returns null`() {
        setupDb()
        assertNull(PreKeyStore().consumePreKey(USER_ID, DEVICE_ID))
        assertNull(PreKeyStore().peekPreKey(USER_ID, DEVICE_ID))
    }

    @Test
    fun `re-publish of live id updates data`() {
        setupDb()
        val store = PreKeyStore()
        transaction {
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "old"))
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "new"))
        }
        assertEquals("new", store.peekPreKey(USER_ID, DEVICE_ID)?.publicKeyBase64)
    }

    @Test
    fun `re-publish of consumed id never revives it`() {
        setupDb()
        val store = PreKeyStore()
        transaction { store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "original")) }
        assertEquals("original", store.consumePreKey(USER_ID, DEVICE_ID)?.publicKeyBase64)
        // 已消费 id 即使客户端带着不同公钥重传，也必须字节级不变且永不再发。
        transaction { store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "attacker")) }
        assertNull(store.peekPreKey(USER_ID, DEVICE_ID))
        assertNull(store.consumePreKey(USER_ID, DEVICE_ID))
        transaction {
            val row = SignalKeys.selectAll().where {
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.keyId eq 7)
            }.single()
            assertEquals(PreKeyStore.CONSUMED_PRE_KEY_TYPE, row[SignalKeys.keyType])
            assertEquals("original", row[SignalKeys.keyData])
        }
    }

    @Test
    fun `consume serves oldest uploaded first`() {
        setupDb()
        val store = PreKeyStore()
        transaction {
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(1, "k1"))
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(2, "k2"))
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(3, "k3"))
            // Force distinct upload order independent of clock granularity.
            listOf(1 to 1000L, 2 to 2000L, 3 to 3000L).forEach { (id, ts) ->
                SignalKeys.update({
                    (SignalKeys.userId eq USER_ID) and (SignalKeys.keyId eq id)
                }) {
                    it[createdAt] = ts
                }
            }
        }
        assertEquals(1, store.consumePreKey(USER_ID, DEVICE_ID)?.keyId)
        assertEquals(2, store.consumePreKey(USER_ID, DEVICE_ID)?.keyId)
        assertEquals(3, store.consumePreKey(USER_ID, DEVICE_ID)?.keyId)
        assertNull(store.consumePreKey(USER_ID, DEVICE_ID))
    }

    @Test
    fun `concurrent consume of a single pre-key has exactly one winner`() {
        setupDb()
        val store = PreKeyStore()
        transaction { store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "key-7")) }

        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val wins = AtomicInteger()
        repeat(threads) {
            pool.submit {
                start.await(10, TimeUnit.SECONDS)
                if (store.consumePreKey(USER_ID, DEVICE_ID) != null) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish")
        assertEquals(1, wins.get(), "exactly one concurrent consumer must win the pre-key")
        assertNull(store.peekPreKey(USER_ID, DEVICE_ID))
    }

    @Test
    fun `purge removes only expired consumed keys`() {
        setupDb()
        val store = PreKeyStore()
        transaction {
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(7, "old-consumed"))
            store.uploadPreKeyInTx(USER_ID, DEVICE_ID, PreKeyUpload(8, "active"))
        }
        assertEquals(7, store.consumePreKey(USER_ID, DEVICE_ID)?.keyId)
        // Backdate the consumed row past retention.
        transaction {
            SignalKeys.update({
                (SignalKeys.userId eq USER_ID) and
                    (SignalKeys.deviceId eq DEVICE_ID) and
                    (SignalKeys.keyType eq PreKeyStore.CONSUMED_PRE_KEY_TYPE)
            }) {
                it[createdAt] = System.currentTimeMillis() - 31L * 24 * 3600 * 1000
            }
        }
        assertEquals(1, store.purgeConsumedPreKeys(retentionDays = 30))
        // Active key untouched; consumed row gone.
        assertEquals("active", store.peekPreKey(USER_ID, DEVICE_ID)?.publicKeyBase64)
        transaction {
            assertTrue(
                SignalKeys.selectAll().where {
                    (SignalKeys.userId eq USER_ID) and
                        (SignalKeys.keyType eq PreKeyStore.CONSUMED_PRE_KEY_TYPE)
                }.empty()
            )
        }
    }

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:pre-key-store-${kotlin.random.Random.nextInt(1_000_000)}-${counter.incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            Users.insert {
                it[Users.id] = USER_ID
                it[Users.name] = "pre-key-test"
                it[Users.email] = "pre-key-test@example.test"
                it[Users.passwordHash] = "x"
            }
        }
    }

    private companion object {
        const val USER_ID = "pre-key-user"
        const val DEVICE_ID = 1
        val counter = AtomicInteger()
    }
}
