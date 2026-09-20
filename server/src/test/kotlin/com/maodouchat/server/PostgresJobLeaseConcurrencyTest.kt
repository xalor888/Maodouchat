package com.maodouchat.server

import com.maodouchat.server.db.JobLeases
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.service.JobLease
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实 PostgreSQL 下的后台任务租约（JobLease）并发与故障恢复测试（G42）。
 *
 * 验证目标：
 * 1. 真 PG 行级排他锁（SELECT ... FOR UPDATE）与唯一索引互斥：
 *    N 个并发实例在完全无行或已有行的情况下同时抢占同一任务，恰好只有 1 个实例成功，其余皆失败（不抛未捕获异常）。
 * 2. 租约持有者能够成功心跳续期，非持有者续期必然失败。
 * 3. 租约持有者主动释放后，其他实例可立即接管。
 * 4. 租约过期（模拟实例崩溃或掉线未续期）后，新实例在下一个周期可原子接管。
 */
@Tag("postgres")
class PostgresJobLeaseConcurrencyTest {

    private fun <T> withPostgresSchema(block: (scopedUrl: String) -> T): T {
        val baseUrl = System.getenv("POSTGRES_TEST_DATABASE_URL")
            ?: "jdbc:postgresql://127.0.0.1:5432/maodouchat_test?user=maodouchat_test&password=maodouchat_test_password"
        val schema = "maodou_jl_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        require(schema.matches(Regex("^maodou_jl_[a-f0-9]{12}$")))

        DriverManager.getConnection(baseUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") }
        }

        val scopedUrl = baseUrl + if ('?' in baseUrl) "&currentSchema=$schema" else "?currentSchema=$schema"
        return try {
            Database.connect(scopedUrl, driver = "org.postgresql.Driver")
            initDatabase()
            block(scopedUrl)
        } finally {
            DriverManager.getConnection(baseUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE") }
            }
        }
    }

    @Test
    fun `concurrent tryAcquire against empty table allows exactly one winner`() {
        withPostgresSchema {
            val concurrency = 16
            val readyLatch = CountDownLatch(concurrency)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(concurrency)
            val successCount = AtomicInteger(0)
            val winners = ConcurrentHashMap.newKeySet<String>()

            val threads = (1..concurrency).map { i ->
                thread {
                    val lease = JobLease("owner-$i")
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        if (lease.tryAcquire("task_daily_report", 30_000L)) {
                            successCount.incrementAndGet()
                            winners.add(lease.ownerId)
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "All threads ready")
            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads done")
            threads.forEach { it.join() }

            assertEquals(1, successCount.get(), "Exactly one winner should acquire empty lease on Postgres")
            assertEquals(1, winners.size)

            val currentOwner = JobLease().ownerOf("task_daily_report")
            assertEquals(winners.first(), currentOwner)
        }
    }

    @Test
    fun `lease renewal heartbeat is exclusive to current owner and rejects others`() {
        withPostgresSchema {
            val ownerA = JobLease("owner-A")
            val ownerB = JobLease("owner-B")
            val taskName = "task_sync"

            assertTrue(ownerA.tryAcquire(taskName, 10_000L, now = 1_000L))

            // Non-owner heartbeat must return false
            assertFalse(ownerB.heartbeat(taskName, 10_000L, now = 2_000L))

            // Owner heartbeat must succeed and update expiresAt
            assertTrue(ownerA.heartbeat(taskName, 15_000L, now = 2_000L))

            val row = transaction {
                JobLeases.selectAll().where { JobLeases.name eq taskName }.single()
            }
            assertEquals("owner-A", row[JobLeases.owner])
            assertEquals(17_000L, row[JobLeases.expiresAt])
        }
    }

    @Test
    fun `expired lease can be atomically taken over by another instance`() {
        withPostgresSchema {
            val ownerA = JobLease("owner-A")
            val ownerB = JobLease("owner-B")
            val taskName = "task_maintenance"

            // ownerA acquires lease expiring at 5_000
            assertTrue(ownerA.tryAcquire(taskName, 4_000L, now = 1_000L))
            assertEquals("owner-A", ownerA.ownerOf(taskName))

            // Before expiration (now = 3_000), ownerB cannot acquire
            assertFalse(ownerB.tryAcquire(taskName, 5_000L, now = 3_000L))
            assertEquals("owner-A", ownerA.ownerOf(taskName))

            // After expiration (now = 5_001), ownerB can take over
            assertTrue(ownerB.tryAcquire(taskName, 5_000L, now = 5_001L))
            assertEquals("owner-B", ownerA.ownerOf(taskName))
        }
    }

    @Test
    fun `voluntary release enables immediate acquisition by another instance`() {
        withPostgresSchema {
            val ownerA = JobLease("owner-A")
            val ownerB = JobLease("owner-B")
            val taskName = "task_cleanup"

            assertTrue(ownerA.tryAcquire(taskName, 60_000L, now = 1_000L))
            assertFalse(ownerB.tryAcquire(taskName, 60_000L, now = 1_000L))

            // Owner voluntarily releases
            ownerA.release(taskName)
            assertNull(ownerA.ownerOf(taskName))

            // Owner B immediately acquires
            assertTrue(ownerB.tryAcquire(taskName, 60_000L, now = 2_000L))
            assertEquals("owner-B", ownerB.ownerOf(taskName))
        }
    }
}
