package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FriendRepositoryTest {

    @Test
    fun `send and accept request creates friendship without 1-1 chat`() {
        setupDb("friend-accept")
        val repo = FriendRepository()

        val sent = repo.sendRequest("u1", "u2", "hi")
        val created = assertIs<FriendRepository.Result.Success>(sent).request
        assertEquals("PENDING", created.status)

        val accepted = repo.acceptRequest("u2", created.id)
        assertIs<FriendRepository.Result.Success>(accepted)
        assertEquals(listOf("u2"), repo.listFriends("u1").map { it.id })
        assertEquals(listOf("u1"), repo.listFriends("u2").map { it.id })
    }

    @Test
    fun `reject after sender is deleted does not throw`() {
        setupDb("friend-reject-deleted")
        val repo = FriendRepository()
        val created = assertIs<FriendRepository.Result.Success>(
            repo.sendRequest("u1", "u2", "hi")
        ).request

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.deletedAt] = System.currentTimeMillis()
            }
        }

        val rejected = repo.rejectRequest("u2", created.id)
        val dto = assertIs<FriendRepository.Result.Success>(rejected).request
        assertEquals("REJECTED", dto.status)
        assertEquals("u1", dto.fromUser.id)
    }

    @Test
    fun `cancel after recipient is deleted does not throw`() {
        setupDb("friend-cancel-deleted")
        val repo = FriendRepository()
        val created = assertIs<FriendRepository.Result.Success>(
            repo.sendRequest("u1", "u2", "hi")
        ).request

        transaction {
            Users.update({ Users.id eq "u2" }) {
                it[Users.deletedAt] = System.currentTimeMillis()
            }
        }

        val cancelled = repo.cancelRequest("u1", created.id)
        val dto = assertIs<FriendRepository.Result.Success>(cancelled).request
        assertEquals("CANCELLED", dto.status)
        assertEquals("u2", dto.toUser.id)
    }

    @Test
    fun `accept deleted sender returns not found instead of crashing`() {
        setupDb("friend-accept-deleted")
        val repo = FriendRepository()
        val created = assertIs<FriendRepository.Result.Success>(
            repo.sendRequest("u1", "u2", "hi")
        ).request

        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.deletedAt] = System.currentTimeMillis()
            }
        }

        val result = repo.acceptRequest("u2", created.id)
        val failure = assertIs<FriendRepository.Result.Failure>(result)
        assertEquals("USER_NOT_FOUND", failure.code)
    }

    @Test
    fun `duplicate pending request is rejected without 500`() {
        setupDb("friend-dup")
        val repo = FriendRepository()
        assertIs<FriendRepository.Result.Success>(repo.sendRequest("u1", "u2"))
        val again = assertIs<FriendRepository.Result.Failure>(repo.sendRequest("u1", "u2"))
        assertEquals("ALREADY_PENDING", again.code)
        val reverse = assertIs<FriendRepository.Result.Failure>(repo.sendRequest("u2", "u1"))
        assertEquals("INCOMING_PENDING", reverse.code)
    }

    @Test
    fun `incoming list hides requests from deleted users`() {
        setupDb("friend-list-deleted")
        val repo = FriendRepository()
        assertIs<FriendRepository.Result.Success>(repo.sendRequest("u1", "u2"))
        transaction {
            Users.update({ Users.id eq "u1" }) {
                it[Users.deletedAt] = System.currentTimeMillis()
            }
        }
        assertTrue(repo.listIncoming("u2").isEmpty())
    }

    private fun setupDb(label: String) {
        val dbUrl =
            "jdbc:h2:mem:friend-$label-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("u1", "u2", "u3").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
        }
    }
}
