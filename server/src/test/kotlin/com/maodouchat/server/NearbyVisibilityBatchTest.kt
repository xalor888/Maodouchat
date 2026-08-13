package com.maodouchat.server

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.UserLocations
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.NearbyRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
