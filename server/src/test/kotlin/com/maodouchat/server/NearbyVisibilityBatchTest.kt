package com.maodouchat.server

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.UserLocations
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.NearbyRepository
import com.maodouchat.server.repository.getNearby
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyVisibilityBatchTest {

    @Test
    fun `nearby keeps paging past blocked users to fill visible results`() {
        val dbUrl =
            "jdbc:h2:mem:nearby-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2", "u3").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u1"
                it[BlockedUsers.blockedId] = "u2"
            }
            listOf("u1", "u2", "u3").forEach { id ->
                UserLocations.insert {
                    it[UserLocations.userId] = id
                    it[UserLocations.latitude] = 31.2
                    it[UserLocations.longitude] = 121.5
                    it[UserLocations.visible] = true
                    it[UserLocations.updatedAt] = now
                    it[UserLocations.expiresAt] = now + 60_000L
                }
            }
        }

        // u2 sorts before u3 and is blocked; the batch loop must skip it and still return u3.
        val nearby = NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 1)
        assertEquals(1, nearby.size)
        assertEquals("u3", nearby.single().user.id)
    }

    private fun openDb(): Long {
        val dbUrl =
            "jdbc:h2:mem:nearby-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        return System.currentTimeMillis()
    }

    private fun insertUser(id: String, suspendedUntil: Long = 0L) = transaction {
        Users.insert {
            it[Users.id] = id
            it[Users.name] = id
            it[Users.email] = "$id@test.local"
            it[Users.passwordHash] = "x"
            it[Users.suspendedUntil] = suspendedUntil
        }
    }

    private fun insertLocation(
        userId: String,
        latitude: Double,
        longitude: Double,
        visible: Boolean,
        updatedAt: Long,
        expiresAt: Long
    ) = transaction {
        UserLocations.insert {
            it[UserLocations.userId] = userId
            it[UserLocations.latitude] = latitude
            it[UserLocations.longitude] = longitude
            it[UserLocations.visible] = visible
            it[UserLocations.updatedAt] = updatedAt
            it[UserLocations.expiresAt] = expiresAt
        }
    }

    @Test
    fun `nearby returns empty when self location is not visible`() {
        val now = openDb()
        insertUser("u1")
        insertUser("u2")
        insertLocation("u1", 31.2, 121.5, visible = false, updatedAt = now, expiresAt = now + 60_000L)
        insertLocation("u2", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)

        assertTrue(NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 10).isEmpty())
    }

    @Test
    fun `nearby returns empty when self location already expired`() {
        val now = openDb()
        insertUser("u1")
        insertUser("u2")
        insertLocation("u1", 31.2, 121.5, visible = true, updatedAt = now - 120_000L, expiresAt = now - 1_000L)
        insertLocation("u2", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)

        assertTrue(NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 10).isEmpty())
    }

    @Test
    fun `suspended users are excluded from nearby results`() {
        val now = openDb()
        insertUser("u1")
        insertUser("u2", suspendedUntil = now + 3_600_000L)
        insertUser("u3")
        listOf("u1", "u2", "u3").forEach {
            insertLocation(it, 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)
        }

        val nearby = NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 10)
        assertEquals(1, nearby.size)
        assertEquals("u3", nearby.single().user.id)
    }

    @Test
    fun `expired locations of others are excluded`() {
        val now = openDb()
        insertUser("u1")
        insertUser("u2")
        insertUser("u3")
        insertLocation("u1", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)
        insertLocation("u2", 31.2, 121.5, visible = true, updatedAt = now - 120_000L, expiresAt = now - 1_000L)
        insertLocation("u3", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)

        val nearby = NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 10)
        assertEquals(1, nearby.size)
        assertEquals("u3", nearby.single().user.id)
    }

    @Test
    fun `co-located users report the privacy floor distance`() {
        val now = openDb()
        insertUser("u1")
        insertUser("u2")
        // 同坐标：haversine 为 0，按 100m 量化后被隐私下限托到 100m
        insertLocation("u1", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)
        insertLocation("u2", 31.2, 121.5, visible = true, updatedAt = now, expiresAt = now + 60_000L)

        val nearby = NearbyRepository().getNearby("u1", radiusKm = 1.0, limit = 10)
        assertEquals(1, nearby.size)
        assertEquals(100, nearby.single().distanceMeters)
        assertEquals(now, nearby.single().locationUpdatedAt)
    }
}
