package com.maodouchat.server

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.GroupPlayRepository
import com.maodouchat.server.repository.PollRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupPlayBlockedVisibilityTest {

    @Test
    fun `blocked creators entries and votes are hidden from group play lists`() {
        val dbUrl =
            "jdbc:h2:mem:group-play-block-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
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
            Chats.insert {
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "Group"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
            listOf("u1", "u2", "u3").forEach { id ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = "g1"
                    it[ChatParticipants.userId] = id
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = now
                }
            }
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = "u1"
                it[BlockedUsers.blockedId] = "u2"
            }

            GroupChains.insert {
                it[GroupChains.id] = "chain_1"
                it[GroupChains.chatId] = "g1"
                it[GroupChains.creatorId] = "u3"
                it[GroupChains.title] = "Visible"
                it[GroupChains.topic] = "Topic"
                it[GroupChains.maxEntries] = 200
                it[GroupChains.active] = true
                it[GroupChains.createdAt] = now
            }
            GroupChains.insert {
                it[GroupChains.id] = "chain_2"
                it[GroupChains.chatId] = "g1"
                it[GroupChains.creatorId] = "u2"
                it[GroupChains.title] = "Blocked"
                it[GroupChains.topic] = "Topic"
                it[GroupChains.maxEntries] = 200
                it[GroupChains.active] = true
                it[GroupChains.createdAt] = now
            }
            GroupChainEntries.insert {
                it[GroupChainEntries.id] = "ce_1"
                it[GroupChainEntries.chainId] = "chain_1"
                it[GroupChainEntries.userId] = "u2"
                it[GroupChainEntries.sequence] = 1
                it[GroupChainEntries.content] = "blocked entry"
                it[GroupChainEntries.createdAt] = now
            }
            GroupChainEntries.insert {
                it[GroupChainEntries.id] = "ce_2"
                it[GroupChainEntries.chainId] = "chain_1"
                it[GroupChainEntries.userId] = "u3"
                it[GroupChainEntries.sequence] = 2
                it[GroupChainEntries.content] = "visible entry"
                it[GroupChainEntries.createdAt] = now
            }

            GroupPkRounds.insert {
                it[GroupPkRounds.id] = "pk_1"
                it[GroupPkRounds.chatId] = "g1"
                it[GroupPkRounds.creatorId] = "u3"
                it[GroupPkRounds.leftTitle] = "Left"
                it[GroupPkRounds.rightTitle] = "Right"
                it[GroupPkRounds.active] = true
                it[GroupPkRounds.createdAt] = now
            }
            GroupPkRounds.insert {
                it[GroupPkRounds.id] = "pk_2"
                it[GroupPkRounds.chatId] = "g1"
                it[GroupPkRounds.creatorId] = "u2"
                it[GroupPkRounds.leftTitle] = "Left"
                it[GroupPkRounds.rightTitle] = "Right"
                it[GroupPkRounds.active] = true
                it[GroupPkRounds.createdAt] = now
            }
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = "pk_1"
                it[GroupPkVotes.userId] = "u2"
                it[GroupPkVotes.choice] = "left"
                it[GroupPkVotes.votedAt] = now
            }
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = "pk_1"
                it[GroupPkVotes.userId] = "u3"
                it[GroupPkVotes.choice] = "right"
                it[GroupPkVotes.votedAt] = now
            }

            GroupPolls.insert {
                it[GroupPolls.id] = "poll_1"
                it[GroupPolls.chatId] = "g1"
                it[GroupPolls.creatorId] = "u3"
                it[GroupPolls.question] = "Question"
                it[GroupPolls.optionsJson] = """["A","B"]"""
                it[GroupPolls.multi] = false
                it[GroupPolls.anonymous] = false
                it[GroupPolls.closed] = false
                it[GroupPolls.createdAt] = now
            }
            GroupPolls.insert {
                it[GroupPolls.id] = "poll_2"
                it[GroupPolls.chatId] = "g1"
                it[GroupPolls.creatorId] = "u2"
                it[GroupPolls.question] = "Blocked"
                it[GroupPolls.optionsJson] = """["A","B"]"""
                it[GroupPolls.multi] = false
                it[GroupPolls.anonymous] = false
                it[GroupPolls.closed] = false
                it[GroupPolls.createdAt] = now
            }
            GroupPollVotes.insert {
                it[GroupPollVotes.pollId] = "poll_1"
                it[GroupPollVotes.userId] = "u2"
                it[GroupPollVotes.optionIndex] = 0
                it[GroupPollVotes.votedAt] = now
            }
            GroupPollVotes.insert {
                it[GroupPollVotes.pollId] = "poll_1"
                it[GroupPollVotes.userId] = "u3"
                it[GroupPollVotes.optionIndex] = 1
                it[GroupPollVotes.votedAt] = now
            }
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g1"
                it[GroupCheckins.userId] = "u1"
                it[GroupCheckins.checkinDate] = LocalDate.now().toString()
                it[GroupCheckins.streak] = 1
                it[GroupCheckins.totalCount] = 1
                it[GroupCheckins.checkedAt] = now - 2_000L
            }
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g1"
                it[GroupCheckins.userId] = "u2"
                it[GroupCheckins.checkinDate] = LocalDate.now().toString()
                it[GroupCheckins.streak] = 5
                it[GroupCheckins.totalCount] = 10
                it[GroupCheckins.checkedAt] = now - 1_000L
            }
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g1"
                it[GroupCheckins.userId] = "u3"
                it[GroupCheckins.checkinDate] = LocalDate.now().toString()
                it[GroupCheckins.streak] = 2
                it[GroupCheckins.totalCount] = 3
                it[GroupCheckins.checkedAt] = now
            }
            GroupAuditLogs.insert {
                it[GroupAuditLogs.id] = "gal_1"
                it[GroupAuditLogs.chatId] = "g1"
                it[GroupAuditLogs.actorId] = "u2"
                it[GroupAuditLogs.action] = "MEMBER_ADDED"
                it[GroupAuditLogs.createdAt] = now
            }
            GroupAuditLogs.insert {
                it[GroupAuditLogs.id] = "gal_2"
                it[GroupAuditLogs.chatId] = "g1"
                it[GroupAuditLogs.actorId] = "u3"
                it[GroupAuditLogs.action] = "MEMBER_ADDED"
                it[GroupAuditLogs.createdAt] = now
            }
        }

        val chains = GroupCheckinRepository.listChains("g1", "u1", 100)
        assertEquals(listOf("chain_1"), chains.map { it.id })
        assertEquals(listOf("u3"), chains.single().entries.map { it.userId })
        assertNull(GroupCheckinRepository.getChain("chain_2", "u1"))

        val pks = GroupCheckinRepository.listChatPks("g1", "u1", 100)
        assertEquals(listOf("pk_1"), pks.map { it.id })
        assertEquals(0, pks.single().leftCount)
        assertEquals(1, pks.single().rightCount)
        assertEquals(1, pks.single().totalVoters)
        assertNull(GroupCheckinRepository.getPk("pk_2", "u1"))

        val polls = GroupPlayRepository.listChatPolls("g1", "u1", 100)
        assertEquals(listOf("poll_1"), polls.map { it.id })
        assertEquals(listOf(0, 1), polls.single().counts)
        assertEquals(1, polls.single().totalVoters)
        assertNull(GroupPlayRepository.getPoll("poll_2", "u1"))

        val snapshots = PollRepository.listChatPollSnapshots("g1", 100, viewerId = "u1")
        assertEquals(listOf("poll_1"), snapshots.map { it.id })
        assertEquals(listOf(0, 1), snapshots.single().counts)
        assertEquals(1, snapshots.single().totalVoters)

        val ranking = GroupCheckinRepository.checkinRanking("g1", 100, viewerId = "u1")
        assertEquals(listOf("u3", "u1"), ranking.map { it.userId })

        val mine = GroupCheckinRepository.myCheckin("g1", "u1")!!
        assertEquals(2, mine.todayCount)
        assertEquals(1, mine.todayRank)

        val members = ChatRepository().getGroupMembers("g1", viewerId = "u1")
        assertEquals(setOf("u1", "u3"), members.map { it.userId }.toSet())

        val audit = ChatRepository().getGroupAudit("g1", 100, 0, viewerId = "u1")
        assertEquals(listOf("gal_2"), audit.map { it.id })
        val auditFirstPage = ChatRepository().getGroupAudit("g1", 1, 0, viewerId = "u1")
        assertEquals(listOf("gal_2"), auditFirstPage.map { it.id })

        val chat = ChatRepository().getChatById("g1", viewerId = "u1")!!
        assertEquals(setOf("u1", "u3"), chat.participants.map { it.id }.toSet())
        val chatList = ChatRepository().getChatsForUser("u1")
        assertEquals(setOf("u1", "u3"), chatList.single().participants.map { it.id }.toSet())

        val created = ChatRepository().createChat(
            participantIds = listOf("u1", "u2", "u3"),
            isGroup = true,
            groupName = "Created",
            creatorId = "u1"
        )
        assertEquals(setOf("u1", "u3"), created.participants.map { it.id }.toSet())
    }
}
