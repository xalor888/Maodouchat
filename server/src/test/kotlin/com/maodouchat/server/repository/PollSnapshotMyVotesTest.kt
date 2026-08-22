package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollSnapshotMyVotesTest {

    @Test
    fun `sync snapshot fills myVotes for viewer and leaves them empty without viewer`() {
        val dbUrl =
            "jdbc:h2:mem:poll-snapshot-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val now = System.currentTimeMillis()
        transaction {
            listOf("u1", "u2").forEach { id ->
                Users.insert {
                    it[Users.id] = id
                    it[Users.name] = id
                    it[Users.email] = "$id@test.local"
                    it[Users.passwordHash] = "x"
                }
            }
            Chats.insert {
                it[id] = "g1"
                it[isGroup] = true
                it[chatType] = "GROUP"
                it[groupName] = "Group"
                it[lastMessageType] = "TEXT"
                it[lastMessageTime] = now
            }
            listOf("u1", "u2").forEach { id ->
                ChatParticipants.insert {
                    it[chatId] = "g1"
                    it[userId] = id
                    it[role] = "MEMBER"
                    it[joinedAt] = now
                }
            }
            GroupPolls.insert {
                it[id] = "poll_1"
                it[chatId] = "g1"
                it[creatorId] = "u2"
                it[question] = "Q"
                it[optionsJson] = """["A","B","C"]"""
                it[multi] = true
                it[anonymous] = false
                it[closed] = false
                it[createdAt] = now
            }
            GroupPollVotes.insert {
                it[pollId] = "poll_1"
                it[userId] = "u1"
                it[optionIndex] = 0
                it[votedAt] = now
            }
            GroupPollVotes.insert {
                it[pollId] = "poll_1"
                it[userId] = "u1"
                it[optionIndex] = 2
                it[votedAt] = now
            }
            GroupPollVotes.insert {
                it[pollId] = "poll_1"
                it[userId] = "u2"
                it[optionIndex] = 1
                it[votedAt] = now
            }
        }

        val mine = PollRepository.listChatPollSnapshots("g1", 30, viewerId = "u1").single()
        assertEquals("u2", mine.creatorId)
        assertEquals(listOf(0, 2), mine.myVotes)
        assertEquals(listOf(1, 1, 1), mine.counts)
        assertEquals(2, mine.totalVoters)

        val anonymousViewer = PollRepository.listChatPollSnapshots("g1", 30, viewerId = null).single()
        assertTrue(anonymousViewer.myVotes.isEmpty())
        assertEquals("u2", anonymousViewer.creatorId)
    }
}
