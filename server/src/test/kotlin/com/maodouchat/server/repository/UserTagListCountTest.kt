package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserTagListCountTest {

    @Test
    fun `listTags aggregates assignment counts without slice`() {
        val dbUrl =
            "jdbc:h2:mem:user-tag-count-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
        }

        val repo = UserTagRepository()
        val tag = repo.createTag("risk", "#ff0000", "high risk", "HIGH", isSystem = false, createdBy = "admin")
        val unused = repo.createTag("idle", "#00ff00", null, "LOW", isSystem = false, createdBy = "admin")
        assertNotNull(repo.assignTags("u1", listOf(tag.id), "MANUAL", "admin"))
        assertNotNull(repo.assignTags("u2", listOf(tag.id), "MANUAL", "admin"))

        val listed = repo.listTags()
        assertEquals(2L, listed.first { it.id == tag.id }.userCount)
        assertEquals(0L, listed.first { it.id == unused.id }.userCount)
    }
}
