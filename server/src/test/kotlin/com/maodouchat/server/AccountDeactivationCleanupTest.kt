package com.maodouchat.server

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.DeviceEventSequences
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountDeactivationCleanupTest {

    @Test
    fun `account deactivation removes per user operational and group records`() {
        val dbUrl =
            "jdbc:h2:mem:deactivate-test-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()

        val password = "correct horse battery staple"
        val now = System.currentTimeMillis()
        transaction {
            insertUser("u_del", password)
            insertUser("u_other", password)
            UserTags.insert {
                it[UserTags.id] = "tag_1"
                it[UserTags.name] = "Test"
                it[UserTags.color] = "#000000"
                it[UserTags.riskLevel] = "NONE"
                it[UserTags.createdAt] = now
                it[UserTags.updatedAt] = now
            }
            SystemAnnouncements.insert {
                it[SystemAnnouncements.id] = "ann_1"
                it[SystemAnnouncements.title] = "Notice"
                it[SystemAnnouncements.content] = "Hello"
                it[SystemAnnouncements.startsAt] = now
                it[SystemAnnouncements.expiresAt] = now + 86_400_000L
                it[SystemAnnouncements.createdAt] = now
                it[SystemAnnouncements.updatedAt] = now
            }
            GroupChains.insert {
                it[GroupChains.id] = "chain_1"
                it[GroupChains.chatId] = "g_1"
                it[GroupChains.creatorId] = "u_other"
                it[GroupChains.title] = "Chain"
                it[GroupChains.topic] = "Topic"
                it[GroupChains.maxEntries] = 200
                it[GroupChains.active] = true
                it[GroupChains.createdAt] = now
            }
            GroupPkRounds.insert {
                it[GroupPkRounds.id] = "pk_1"
                it[GroupPkRounds.chatId] = "g_1"
                it[GroupPkRounds.creatorId] = "u_other"
                it[GroupPkRounds.leftTitle] = "Left"
                it[GroupPkRounds.rightTitle] = "Right"
                it[GroupPkRounds.active] = true
                it[GroupPkRounds.createdAt] = now
            }

            AnnouncementAcks.insert {
                it[AnnouncementAcks.announcementId] = "ann_1"
                it[AnnouncementAcks.userId] = "u_del"
                it[AnnouncementAcks.ackedAt] = now
            }
            AnnouncementAcks.insert {
                it[AnnouncementAcks.announcementId] = "ann_1"
                it[AnnouncementAcks.userId] = "u_other"
                it[AnnouncementAcks.ackedAt] = now
            }
            UserTagAssignments.insert {
                it[UserTagAssignments.tagId] = "tag_1"
                it[UserTagAssignments.userId] = "u_del"
                it[UserTagAssignments.assignmentSource] = "MANUAL"
                it[UserTagAssignments.assignedBy] = "admin"
                it[UserTagAssignments.createdAt] = now
            }
            UserTagAssignments.insert {
                it[UserTagAssignments.tagId] = "tag_1"
                it[UserTagAssignments.userId] = "u_other"
                it[UserTagAssignments.assignmentSource] = "MANUAL"
                it[UserTagAssignments.assignedBy] = "admin"
                it[UserTagAssignments.createdAt] = now
            }
            DeviceEventSequences.insert {
                it[DeviceEventSequences.userId] = "u_del"
                it[DeviceEventSequences.deviceId] = 1
                it[DeviceEventSequences.eventType] = "WS_SYNC"
                it[DeviceEventSequences.lastAppliedSeq] = 3L
                it[DeviceEventSequences.lastEventAt] = now
            }
            DeviceEventConsistencyLog.insert {
                it[DeviceEventConsistencyLog.id] = "dev_1"
                it[DeviceEventConsistencyLog.userId] = "u_del"
                it[DeviceEventConsistencyLog.deviceId] = 1
                it[DeviceEventConsistencyLog.eventType] = "WS_SYNC"
                it[DeviceEventConsistencyLog.seq] = 3L
                it[DeviceEventConsistencyLog.status] = "DUPLICATE"
                it[DeviceEventConsistencyLog.firstSeenAt] = now
                it[DeviceEventConsistencyLog.lastSeenAt] = now
            }
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g_1"
                it[GroupCheckins.userId] = "u_del"
                it[GroupCheckins.checkinDate] = "2026-08-13"
                it[GroupCheckins.streak] = 1
                it[GroupCheckins.totalCount] = 1
                it[GroupCheckins.checkedAt] = now
            }
            GroupCheckins.insert {
                it[GroupCheckins.chatId] = "g_1"
                it[GroupCheckins.userId] = "u_other"
                it[GroupCheckins.checkinDate] = "2026-08-13"
                it[GroupCheckins.streak] = 1
                it[GroupCheckins.totalCount] = 1
                it[GroupCheckins.checkedAt] = now
            }
            GroupChainEntries.insert {
                it[GroupChainEntries.id] = "ce_1"
                it[GroupChainEntries.chainId] = "chain_1"
                it[GroupChainEntries.userId] = "u_del"
                it[GroupChainEntries.sequence] = 1
                it[GroupChainEntries.content] = "entry"
                it[GroupChainEntries.createdAt] = now
            }
            GroupChainEntries.insert {
                it[GroupChainEntries.id] = "ce_2"
                it[GroupChainEntries.chainId] = "chain_1"
                it[GroupChainEntries.userId] = "u_other"
                it[GroupChainEntries.sequence] = 2
                it[GroupChainEntries.content] = "keep"
                it[GroupChainEntries.createdAt] = now
            }
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = "pk_1"
                it[GroupPkVotes.userId] = "u_del"
                it[GroupPkVotes.choice] = "left"
                it[GroupPkVotes.votedAt] = now
            }
            GroupPkVotes.insert {
                it[GroupPkVotes.pkId] = "pk_1"
                it[GroupPkVotes.userId] = "u_other"
                it[GroupPkVotes.choice] = "right"
                it[GroupPkVotes.votedAt] = now
            }
        }

        val result = UserRepository().deleteAccount("u_del", password)
        assertNotNull(result, "deleteAccount should complete")

        transaction {
            assertTrue(AnnouncementAcks.selectAll().where { AnnouncementAcks.userId eq "u_del" }.empty())
            assertTrue(UserTagAssignments.selectAll().where { UserTagAssignments.userId eq "u_del" }.empty())
            assertTrue(DeviceEventSequences.selectAll().where { DeviceEventSequences.userId eq "u_del" }.empty())
            assertTrue(DeviceEventConsistencyLog.selectAll().where { DeviceEventConsistencyLog.userId eq "u_del" }.empty())
            assertTrue(GroupCheckins.selectAll().where { GroupCheckins.userId eq "u_del" }.empty())
            assertTrue(GroupChainEntries.selectAll().where { GroupChainEntries.userId eq "u_del" }.empty())
            assertTrue(GroupPkVotes.selectAll().where { GroupPkVotes.userId eq "u_del" }.empty())

            assertTrue(AnnouncementAcks.selectAll().where { AnnouncementAcks.userId eq "u_other" }.count() == 1L)
            assertTrue(UserTagAssignments.selectAll().where { UserTagAssignments.userId eq "u_other" }.count() == 1L)
            assertTrue(GroupCheckins.selectAll().where { GroupCheckins.userId eq "u_other" }.count() == 1L)
            assertTrue(GroupChainEntries.selectAll().where { GroupChainEntries.userId eq "u_other" }.count() == 1L)
            assertTrue(GroupPkVotes.selectAll().where { GroupPkVotes.userId eq "u_other" }.count() == 1L)
        }
    }

    private fun insertUser(id: String, password: String) {
        Users.insert {
            it[Users.id] = id
            it[Users.name] = id
            it[Users.email] = "$id@test.local"
            it[Users.passwordHash] = BCrypt.withDefaults().hashToString(12, password.toCharArray())
        }
    }
}
