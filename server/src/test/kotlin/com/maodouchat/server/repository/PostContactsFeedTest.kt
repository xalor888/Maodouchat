package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostContactsFeedTest {

    @Test
    fun `contacts post from friendship without direct chat appears in feed`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            Friendships.insert {
                it[userLowId] = "u1"
                it[userHighId] = "u2"
                it[createdAt] = now
            }
            Posts.insert {
                it[id] = "post_friend"
                it[authorId] = "u2"
                it[content] = "friends only"
                it[imageUrls] = "[]"
                it[visibility] = "CONTACTS"
                it[createdAt] = now
            }
            Posts.insert {
                it[id] = "post_stranger"
                it[authorId] = "u3"
                it[content] = "not a friend"
                it[imageUrls] = "[]"
                it[visibility] = "CONTACTS"
                it[createdAt] = now
            }
        }

        val feed = PostRepository().getFeed("u1", limit = 50)
        assertEquals(listOf("post_friend"), feed.map { it.id })
    }

    @Test
    fun `group membership alone does not expose contacts posts`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            Chats.insert {
                it[id] = "g1"
                it[isGroup] = true
                it[chatType] = "GROUP"
                it[groupName] = "Group"
                it[lastMessageType] = "TEXT"
                it[lastMessageTime] = now
            }
            listOf("u1", "u3").forEach { id ->
                ChatParticipants.insert {
                    it[chatId] = "g1"
                    it[userId] = id
                    it[role] = "MEMBER"
                    it[joinedAt] = now
                }
            }
            Posts.insert {
                it[id] = "post_group_member"
                it[authorId] = "u3"
                it[content] = "group stranger"
                it[imageUrls] = "[]"
                it[visibility] = "CONTACTS"
                it[createdAt] = now
            }
        }

        val feed = PostRepository().getFeed("u1", limit = 50)
        assertTrue(feed.none { it.id == "post_group_member" })
    }

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:post-contacts-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
