package com.maodouchat.server.service

import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobLeaseTest {

    private var database: Database? = null

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `acquire release and reacquire`() {
        setupDb()
        val lease = JobLease("owner-1") { 1_000L }
        assertTrue(lease.tryAcquire("gc", 60_000L))
        assertEquals("owner-1", lease.ownerOf("gc"))
        // 同持有者重复抢占成功（幂等）。
        assertTrue(lease.tryAcquire("gc", 60_000L))
        lease.release("gc")
        assertNull(lease.ownerOf("gc"))
        assertTrue(lease.tryAcquire("gc", 60_000L))
    }

    @Test
    fun `live lease blocks others, expired lease is taken over`() {
        var now = 1_000L
        val a = JobLease("a") { now }
        val b = JobLease("b") { now }
        setupDb()
        assertTrue(a.tryAcquire("gc", 60_000L))
        assertFalse(b.tryAcquire("gc", 60_000L))
        // 未过期：对方续约失败。
        assertFalse(b.heartbeat("gc", 60_000L))
        // 过期后对方可接管。
        now += 60_001L
        assertTrue(b.tryAcquire("gc", 60_000L))
        assertEquals("b", b.ownerOf("gc"))
        // 前任续约/释放均失效。
        assertFalse(a.heartbeat("gc", 60_000L))
        a.release("gc")
        assertEquals("b", b.ownerOf("gc"))
        assertTrue(b.heartbeat("gc", 60_000L))
    }

    @Test
    fun `concurrent acquire has exactly one winner`() {
        setupDb()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val wins = AtomicInteger()
        repeat(threads) { i ->
            pool.submit {
                start.await(10, TimeUnit.SECONDS)
                if (JobLease("owner-$i") { System.currentTimeMillis() }.tryAcquire("gc", 60_000L)) {
                    wins.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:job-lease-${kotlin.random.Random.nextInt(1_000_000)}-${counter.incrementAndGet()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
    }

    private companion object {
        val counter = AtomicInteger()
    }
}
