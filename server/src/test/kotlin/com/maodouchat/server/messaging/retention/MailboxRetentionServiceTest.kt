package com.maodouchat.server.messaging.retention

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailboxRetentionServiceTest {
    private var database: Database? = null

    @AfterEach
    fun closeDatabase() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `purge batch applies acknowledged unacknowledged and retired device policies`() {
        setupDatabase()
        val now = 10_000L
        transaction {
            insertEnvelope("ack-old", now - 100, acknowledgedAt = now - 100)
            insertEnvelope("unacked-old", now - 100)
            insertEnvelope("retired-old", now - 100, recipientDeviceId = 2)
            insertEnvelope("fresh", now - 5)
        }

        val result = MailboxRetentionService { now }.purgeBatch(
            policy = MailboxRetentionPolicy(
                acknowledgedRetentionMs = 10,
                unacknowledgedRetentionMs = 20,
                retiredDeviceRetentionMs = 30,
            ),
        )

        assertEquals(1, result.acknowledged)
        assertEquals(1, result.unacknowledged)
        assertEquals(1, result.deviceRetired)
        transaction {
            assertEquals(listOf("fresh"), MessagingV2Envelopes.selectAll().map { it[MessagingV2Envelopes.id] })
        }
    }

    @Test
    fun `device retirement cleanup is immediate and idempotent`() {
        setupDatabase()
        transaction {
            insertEnvelope("device-one", 1, recipientDeviceId = 1)
            insertEnvelope("device-two", 1, recipientDeviceId = 2)
        }
        val service = MailboxRetentionService()

        assertEquals(1, service.purgeRetiredDevice("recipient", 2))
        assertEquals(0, service.purgeRetiredDevice("recipient", 2))
        transaction {
            assertTrue(MessagingV2Envelopes.selectAll().any { it[MessagingV2Envelopes.id] == "device-one" })
        }
    }

    private fun setupDatabase() {
        database = Database.connect(
            "jdbc:h2:mem:mailbox-retention-${AtomicInteger().incrementAndGet()}-${kotlin.random.Random.nextInt()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        transaction {
            Users.insert {
                it[id] = "sender"
                it[name] = "Sender"
                it[email] = "sender@example.com"
                it[passwordHash] = "test"
            }
            Users.insert {
                it[id] = "recipient"
                it[name] = "Recipient"
                it[email] = "recipient@example.com"
                it[passwordHash] = "test"
            }
            Chats.insert {
                it[id] = "chat-active"
                it[isGroup] = false
                it[chatType] = "DIRECT"
            }
            SignalDevices.insert {
                it[userId] = "recipient"
                it[deviceId] = 1
                it[deviceName] = "recipient phone"
                it[status] = "CONFIRMED"
                it[confirmedAt] = 1L
                it[confirmedByDeviceId] = 1
                it[createdAt] = 1L
                it[lastSeenAt] = 1L
            }
        }
    }

    private fun insertEnvelope(
        id: String,
        timestamp: Long,
        acknowledgedAt: Long? = null,
        recipientDeviceId: Int = 1,
    ) {
        val messageId = "message-$id"
        MessagingV2Messages.insert {
            it[MessagingV2Messages.id] = messageId
            it[MessagingV2Messages.conversationId] = "chat-active"
            it[MessagingV2Messages.senderUserId] = "sender"
            it[MessagingV2Messages.senderDeviceId] = 1
            it[MessagingV2Messages.kind] = "DATA"
            it[MessagingV2Messages.recordClass] = "MESSAGE"
            it[MessagingV2Messages.groupRevision] = null
            it[MessagingV2Messages.clientTimestamp] = timestamp
            it[MessagingV2Messages.serverTimestamp] = timestamp
            it[MessagingV2Messages.requestDigest] = "digest-$id"
        }
        MessagingV2Envelopes.insert {
            it[MessagingV2Envelopes.id] = id
            it[MessagingV2Envelopes.messageId] = messageId
            it[MessagingV2Envelopes.recipientUserId] = "recipient"
            it[MessagingV2Envelopes.recipientDeviceId] = recipientDeviceId
            it[MessagingV2Envelopes.ciphertextType] = "PREKEY"
            it[MessagingV2Envelopes.ciphertext] = "cipher-$id"
            it[MessagingV2Envelopes.serverTimestamp] = timestamp
            it[MessagingV2Envelopes.acknowledgedAt] = acknowledgedAt
        }
    }
}
