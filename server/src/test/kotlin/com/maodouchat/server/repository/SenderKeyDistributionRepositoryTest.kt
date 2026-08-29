package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** v2 Sender Key coverage is derived from committed mailbox envelopes only. */
class SenderKeyDistributionRepositoryTest {

    private var database: Database? = null

    private fun setupDb() {
        val dbUrl =
            "jdbc:h2:mem:skd-repo-${kotlin.random.Random.nextInt(1_000_000)}-${AtomicInteger().incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(dbUrl, driver = "org.h2.Driver", user = "sa", password = "")
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
                it[Chats.id] = "g1"
                it[Chats.isGroup] = true
                it[Chats.chatType] = "GROUP"
                it[Chats.groupName] = "skd"
                it[Chats.lastMessageType] = "TEXT"
                it[Chats.lastMessageTime] = now
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        database = null
    }

    @Test
    fun `committed v2 sender key mailbox is authoritative coverage`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            MessagingV2Messages.insert {
                it[MessagingV2Messages.id] = "sk_v2_1"
                it[MessagingV2Messages.conversationId] = "g1"
                it[MessagingV2Messages.senderUserId] = "u1"
                it[MessagingV2Messages.senderDeviceId] = 1
                it[MessagingV2Messages.kind] = "SENDER_KEY"
                it[MessagingV2Messages.recordClass] = "INTERNAL"
                it[MessagingV2Messages.groupRevision] = 11L
                it[MessagingV2Messages.clientTimestamp] = now
                it[MessagingV2Messages.serverTimestamp] = now
                it[MessagingV2Messages.requestDigest] = "digest-v2-sender-key"
            }
            listOf(1, 2).forEach { deviceId ->
                MessagingV2Envelopes.insert {
                    it[MessagingV2Envelopes.id] = "env-v2-$deviceId"
                    it[MessagingV2Envelopes.messageId] = "sk_v2_1"
                    it[MessagingV2Envelopes.recipientUserId] = "u2"
                    it[MessagingV2Envelopes.recipientDeviceId] = deviceId
                    it[MessagingV2Envelopes.ciphertextType] = "DIRECT"
                    it[MessagingV2Envelopes.ciphertext] = "ciphertext-$deviceId"
                    it[MessagingV2Envelopes.serverTimestamp] = now
                }
            }
        }

        val status = SenderKeyDistributionRepository().getStatus(
            chatId = "g1",
            senderId = "u1",
            epoch = 11L,
            expectedTargets = setOf("u2" to 1, "u2" to 2),
        )
        assertEquals(2, status.total)
        assertEquals(2, status.sent)
        assertEquals(0, status.pending)
        assertEquals(0, status.failed)
    }

    @Test
    fun `coverage unions all committed sender key envelopes for the epoch`() {
        setupDb()
        val now = System.currentTimeMillis()
        transaction {
            listOf(
                "sk_v2_epoch_1" to 1,
                "sk_v2_epoch_2" to 2,
            ).forEach { (messageId, deviceId) ->
                MessagingV2Messages.insert {
                    it[MessagingV2Messages.id] = messageId
                    it[MessagingV2Messages.conversationId] = "g1"
                    it[MessagingV2Messages.senderUserId] = "u1"
                    it[MessagingV2Messages.senderDeviceId] = 1
                    it[MessagingV2Messages.kind] = "SENDER_KEY"
                    it[MessagingV2Messages.recordClass] = "INTERNAL"
                    it[MessagingV2Messages.groupRevision] = 12L
                    it[MessagingV2Messages.clientTimestamp] = now
                    it[MessagingV2Messages.serverTimestamp] = now + deviceId
                    it[MessagingV2Messages.requestDigest] = "digest-$messageId"
                }
                MessagingV2Envelopes.insert {
                    it[MessagingV2Envelopes.id] = "env-$messageId"
                    it[MessagingV2Envelopes.messageId] = messageId
                    it[MessagingV2Envelopes.recipientUserId] = "u2"
                    it[MessagingV2Envelopes.recipientDeviceId] = deviceId
                    it[MessagingV2Envelopes.ciphertextType] = "DIRECT"
                    it[MessagingV2Envelopes.ciphertext] = "ciphertext-$deviceId"
                    it[MessagingV2Envelopes.serverTimestamp] = now
                }
            }
        }

        val status = SenderKeyDistributionRepository().getStatus(
            chatId = "g1",
            senderId = "u1",
            epoch = 12L,
            expectedTargets = setOf("u2" to 1, "u2" to 2),
        )

        assertEquals(2, status.sent)
        assertEquals(0, status.pending)
    }
}
