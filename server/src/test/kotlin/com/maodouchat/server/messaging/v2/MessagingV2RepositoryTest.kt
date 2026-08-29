package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CreateReportRequest
import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.repository.ReportRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2RepositoryTest {
    private var database: Database? = null

    @AfterEach
    fun closeDatabase() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `group message is durable for offline devices and disappears only after ack`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        val result = repository.send(groupCommand())

        transaction {
            assertTrue(Users.selectAll().where { Users.isOnline eq false }.count() >= 3)
        }
        assertFalse(result.idempotentReplay)
        assertEquals(2, result.envelopeCount)
        val bobInbox = repository.pending("bob", 1, 100)
        assertEquals("cipher-for-bob", bobInbox.envelopes.single().ciphertext)

        assertEquals(1, repository.acknowledge("bob", 1, setOf(bobInbox.envelopes.single().envelopeId)))
        assertEquals(1, repository.acknowledge("bob", 1, setOf(bobInbox.envelopes.single().envelopeId)))
        assertTrue(repository.pending("bob", 1, 100).envelopes.isEmpty())
        assertEquals(1, repository.pending("carol", 1, 100).envelopes.size)
    }

    @Test
    fun `send requires every current group device without checking online presence`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        val incomplete = groupCommand().copy(envelopes = groupCommand().envelopes.take(1))

        val error = assertFailsWith<MessagingV2CoverageException> { repository.send(incomplete) }
        assertEquals(setOf(DeviceTarget("carol", 1)), error.missing)
    }

    @Test
    fun `same encrypted request is idempotent but changed ciphertext conflicts`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        repository.send(groupCommand())

        val replay = repository.send(groupCommand())
        assertTrue(replay.idempotentReplay)
        assertEquals(2, replay.envelopeCount)

        val changed = groupCommand().copy(
            envelopes = groupCommand().envelopes.map {
                if (it.target.userId == "bob") it.copy(ciphertext = "changed") else it
            },
        )
        assertFailsWith<MessagingV2DuplicateMessageException> { repository.send(changed) }
    }

    @Test
    fun `idempotent replay bypasses new message admission limit`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        var admissions = 0
        repository.send(groupCommand()) { admissions++; true }

        val replay = repository.send(groupCommand()) { admissions++; false }

        assertTrue(replay.idempotentReplay)
        assertEquals(1, admissions)
        assertFailsWith<MessagingV2RateLimitedException> {
            repository.send(groupCommand().copy(id = "message-2")) { false }
        }
    }

    @Test
    fun `stale group revision is rejected before inserting envelopes`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }

        val error = assertFailsWith<MessagingV2RevisionMismatchException> {
            repository.send(groupCommand().copy(groupRevision = 6L))
        }
        assertEquals(7L, error.expected)
        assertTrue(repository.pending("bob", 1, 100).envelopes.isEmpty())
    }

    @Test
    fun `snapshot includes offline confirmed devices and excludes current sender device`() {
        seedGroup()
        transaction {
            SignalDevices.insert {
                it[userId] = "alice"
                it[deviceId] = 2
                it[deviceName] = "alice tablet"
                it[status] = "CONFIRMED"
                it[confirmedAt] = 1L
                it[confirmedByDeviceId] = 1
                it[createdAt] = 1L
                it[lastSeenAt] = 1L
            }
            seedSignalBundle("alice", 2)
        }

        val snapshot = MessagingV2Repository().conversationSnapshot("group-1", "alice", 1)

        assertEquals(7L, snapshot.memberRevision)
        assertEquals(listOf("alice", "bob", "carol"), snapshot.participantUserIds)
        assertEquals(
            setOf(DeviceTarget("alice", 2), DeviceTarget("bob", 1), DeviceTarget("carol", 1)),
            snapshot.targets.mapTo(linkedSetOf()) { DeviceTarget(it.userId, it.deviceId) },
        )
    }

    @Test
    fun `group snapshot and send exclude members blocked by sender`() {
        seedGroup()
        transaction {
            BlockedUsers.insert {
                it[blockerId] = "alice"
                it[blockedId] = "bob"
            }
        }
        val repository = MessagingV2Repository { 10_000L }

        val snapshot = repository.conversationSnapshot("group-1", "alice", 1)
        assertEquals(
            setOf(DeviceTarget("carol", 1)),
            snapshot.targets.mapTo(linkedSetOf()) { DeviceTarget(it.userId, it.deviceId) },
        )
        val result = repository.send(
            groupCommand().copy(envelopes = groupCommand().envelopes.filter { it.target.userId == "carol" }),
        )

        assertEquals(setOf("carol"), result.recipientUserIds)
        assertTrue(repository.pending("bob", 1, 10).envelopes.isEmpty())
        assertEquals(1, repository.pending("carol", 1, 10).envelopes.size)
    }

    @Test
    fun `group send ignores confirmed device without complete key bundle`() {
        seedGroup()
        transaction {
            SignalDevices.insert {
                it[userId] = "carol"
                it[deviceId] = 2
                it[deviceName] = "carol incomplete tablet"
                it[status] = "CONFIRMED"
                it[confirmedAt] = 1L
                it[confirmedByDeviceId] = 1
                it[createdAt] = 1L
                it[lastSeenAt] = 1L
            }
        }
        val repository = MessagingV2Repository { 10_000L }

        val snapshot = repository.conversationSnapshot("group-1", "alice", 1)
        assertFalse(snapshot.targets.any { it.userId == "carol" && it.deviceId == 2 })

        val result = repository.send(groupCommand())
        assertEquals(2, result.envelopeCount)
        assertTrue(repository.pending("carol", 2, 10).envelopes.isEmpty())
        assertEquals(1, repository.pending("carol", 1, 10).envelopes.size)
    }

    @Test
    fun `durable sender key request is accepted for groups and rejected for direct chats`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        val request = groupCommand().copy(
            id = "key-request-1",
            kind = "KEY_REQUEST",
            envelopes = groupCommand().envelopes.map { it.copy(ciphertextType = "PREKEY") },
        )

        val result = repository.send(request)
        assertEquals(2, result.envelopeCount)
        assertEquals("KEY_REQUEST", repository.pending("bob", 1, 10).envelopes.single().kind)

        transaction {
            Chats.update({ Chats.id eq "group-1" }) { it[isGroup] = false }
        }
        assertFailsWith<MessagingV2ProtocolViolationException> {
            repository.send(request.copy(id = "key-request-direct"))
        }
    }

    @Test
    fun `message restricted account cannot submit user mutations`() {
        seedGroup()
        transaction {
            Users.update({ Users.id eq "alice" }) {
                it[messageRestrictedUntil] = 20_000L
            }
        }

        assertFailsWith<MessagingV2SenderRestrictedException> {
            MessagingV2Repository { 10_000L }.send(groupCommand())
        }
    }

    @Test
    fun `muted member may still send receipts but not user mutations`() {
        seedGroup()
        transaction {
            ChatParticipants.update({
                (ChatParticipants.chatId eq "group-1") and
                    (ChatParticipants.userId eq "alice")
            }) {
                it[mutedUntil] = 20_000L
            }
        }
        val repository = MessagingV2Repository { 10_000L }

        assertFailsWith<MessagingV2SenderMutedException> {
            repository.send(groupCommand())
        }
        val receipt = repository.send(groupCommand().copy(id = "receipt-1", kind = "RECEIPT"))
        assertEquals(2, receipt.envelopeCount)
    }

    @Test
    fun `channel member cannot submit data but may submit encrypted events`() {
        seedGroup()
        transaction {
            Chats.update({ Chats.id eq "group-1" }) { it[chatType] = "CHANNEL" }
        }
        val bobCommand = groupCommand().copy(
            id = "bob-channel-message",
            senderUserId = "bob",
            envelopes = listOf(
                OutboundEnvelope(DeviceTarget("alice", 1), "SENDER_KEY", "cipher-for-alice"),
                OutboundEnvelope(DeviceTarget("carol", 1), "SENDER_KEY", "cipher-for-carol"),
            ),
        )
        val repository = MessagingV2Repository { 10_000L }

        assertFailsWith<MessagingV2ChannelReadOnlyException> { repository.send(bobCommand) }
        val event = repository.send(bobCommand.copy(id = "bob-channel-event", kind = "EVENT"))
        assertEquals(2, event.envelopeCount)
    }

    @Test
    fun `v2 send commits uploaded attachment atomically`() {
        seedGroup()
        transaction {
            EncryptedAttachments.insert {
                it[id] = "att-1"
                it[chatId] = "group-1"
                it[uploaderId] = "alice"
                it[messageId] = "message-attachment"
                it[cipherSha256] = "a".repeat(64)
                it[cipherSize] = 10L
                it[uploadedBytes] = 10L
                it[status] = "UPLOADED"
                it[createdAt] = 1L
                it[expiresAt] = 20_000L
            }
        }
        val repository = MessagingV2Repository { 10_000L }
        repository.send(
            groupCommand().copy(
                id = "message-attachment",
                attachmentIds = listOf("att-1"),
            ),
        )
        transaction {
            assertEquals("COMMITTED", EncryptedAttachments.selectAll().single()[EncryptedAttachments.status])
            assertEquals(null, EncryptedAttachments.selectAll().single()[EncryptedAttachments.expiresAt])
        }
    }

    @Test
    fun `moderation delete removes v2 transport metadata and attachment ownership`() {
        seedGroup()
        transaction {
            EncryptedAttachments.insert {
                it[id] = "att-moderated"
                it[chatId] = "group-1"
                it[uploaderId] = "alice"
                it[messageId] = "message-1"
                it[cipherSha256] = "b".repeat(64)
                it[cipherSize] = 20L
                it[uploadedBytes] = 20L
                it[status] = "UPLOADED"
                it[createdAt] = 1L
                it[expiresAt] = 20_000L
            }
        }
        val repository = MessagingV2Repository { 10_000L }
        repository.send(groupCommand().copy(attachmentIds = listOf("att-moderated")))
        transaction {
            StarMessages.insert {
                it[userId] = "bob"
                it[messageId] = "message-1"
            }
            PinnedMessages.insert {
                it[chatId] = "group-1"
                it[messageId] = "message-1"
                it[pinnedBy] = "alice"
            }
        }

        val deleted = repository.deleteMessageForModeration("message-1")

        assertEquals("group-1", deleted?.metadata?.conversationId)
        assertEquals(listOf("att-moderated"), deleted?.deletedAttachmentIds)
        assertEquals(null, repository.messageMetadata("message-1"))
        assertTrue(repository.pending("bob", 1, 10).envelopes.isEmpty())
        transaction {
            assertTrue(EncryptedAttachments.selectAll().empty())
            assertTrue(MessagingV2Envelopes.selectAll().empty())
            assertTrue(MessagingV2Messages.selectAll().empty())
            assertTrue(StarMessages.selectAll().empty())
            assertTrue(PinnedMessages.selectAll().empty())
        }
    }

    @Test
    fun `moderation delete removes bot service storage`() {
        seedGroup()
        seedBotParticipant()
        val repository = MessagingV2Repository { 10_000L }
        val message = MessageResponse(
            id = "bot-message-moderated",
            chatId = "group-1",
            senderId = "bot_helper",
            content = "reported bot content",
            timestamp = 9_000L,
        )
        repository.enqueueServiceMessage(message, setOf("alice", "bob", "carol"))
        transaction {
            ServiceMessages.insert {
                it[id] = message.id
                it[chatId] = message.chatId
                it[senderId] = message.senderId
                it[content] = message.content
                it[type] = message.type
                it[timestamp] = message.timestamp
                it[editedAt] = null
                it[deletedAt] = null
            }
            ServiceMessageReactions.insert {
                it[messageId] = message.id
                it[botUserId] = "bot_helper"
                it[emoji] = "ok"
                it[reactedAt] = 9_100L
            }
        }

        assertEquals("bot_helper", repository.deleteMessageForModeration(message.id)?.metadata?.senderUserId)
        transaction {
            assertTrue(ServiceMessages.selectAll().empty())
            assertTrue(ServiceMessageReactions.selectAll().empty())
        }
    }

    @Test
    fun `message reports resolve only user visible v2 records`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }
        repository.send(groupCommand())
        repository.send(groupCommand().copy(id = "receipt-report", kind = "RECEIPT"))

        val reported = ReportRepository().createReport(
            reporterId = "bob",
            request = CreateReportRequest(
                targetType = "MESSAGE",
                targetId = "message-1",
                reason = "spam",
            ),
        )
        val internal = ReportRepository().createReport(
            reporterId = "bob",
            request = CreateReportRequest(
                targetType = "MESSAGE",
                targetId = "receipt-report",
                reason = "spam",
            ),
        )

        assertTrue(reported is ReportRepository.CreateResult.Success)
        assertEquals("group-1", (reported as ReportRepository.CreateResult.Success).report.chatId)
        assertTrue(internal is ReportRepository.CreateResult.Failure)
    }

    @Test
    fun `bot service message is durable for every offline confirmed device`() {
        seedGroup()
        seedBotParticipant()
        val repository = MessagingV2Repository { 10_000L }

        val result = repository.enqueueServiceMessage(
            message = MessageResponse(
                id = "bot-message-1",
                chatId = "group-1",
                senderId = "bot_helper",
                content = "pong",
                timestamp = 9_000L,
            ),
            recipientUserIds = setOf("alice", "bob", "carol"),
        )

        assertEquals(3, result.envelopeCount)
        assertEquals(setOf("alice", "bob", "carol"), result.recipientUserIds)
        val bobEnvelope = repository.pending("bob", 1, 10).envelopes.single()
        assertEquals("SERVICE", bobEnvelope.kind)
        assertEquals("SERVICE_PLAINTEXT", bobEnvelope.ciphertextType)
        assertTrue(bobEnvelope.ciphertext.contains("pong"))
    }

    @Test
    fun `bot service mutation is durable for offline devices`() {
        seedGroup()
        seedBotParticipant()
        val repository = MessagingV2Repository { 10_000L }

        val result = repository.enqueueServiceEvent(
            id = "bot-event-1",
            conversationId = "group-1",
            senderUserId = "bot_helper",
            clientTimestamp = 9_500L,
            event = ServiceMessagingV2Event(
                action = "EDIT",
                targetMessageId = "bot-message-1",
                content = "updated",
                editedAt = 9_500L,
            ),
            recipientUserIds = setOf("alice", "bob", "carol"),
        )

        assertEquals(3, result.envelopeCount)
        val bobEnvelope = repository.pending("bob", 1, 10).envelopes.single()
        assertEquals("SERVICE", bobEnvelope.kind)
        assertEquals("SERVICE_PLAINTEXT", bobEnvelope.ciphertextType)
        assertTrue(bobEnvelope.ciphertext.contains("\"action\":\"EDIT\""))
        assertTrue(bobEnvelope.ciphertext.contains("\"targetMessageId\":\"bot-message-1\""))
    }

    @Test
    fun `bot service mutation rejects malformed event`() {
        seedGroup()
        seedBotParticipant()

        assertFailsWith<IllegalArgumentException> {
            MessagingV2Repository { 10_000L }.enqueueServiceEvent(
                id = "bot-event-invalid",
                conversationId = "group-1",
                senderUserId = "bot_helper",
                clientTimestamp = 9_500L,
                event = ServiceMessagingV2Event(
                    action = "EDIT",
                    targetMessageId = "bot-message-1",
                ),
                recipientUserIds = setOf("bob"),
            )
        }
    }

    @Test
    fun `system delete is durable but other system mutations are rejected`() {
        seedGroup()
        val repository = MessagingV2Repository { 10_000L }

        val result = repository.enqueueServiceEvent(
            id = "system-event-1",
            conversationId = "group-1",
            senderUserId = "system",
            clientTimestamp = 9_500L,
            event = ServiceMessagingV2Event(
                action = "DELETE",
                targetMessageId = "message-1",
            ),
            recipientUserIds = setOf("alice", "bob", "carol"),
        )

        assertEquals(3, result.envelopeCount)
        val envelope = repository.pending("bob", 1, 10).envelopes.single()
        assertEquals("system", envelope.senderUserId)
        assertTrue(envelope.ciphertext.contains("\"action\":\"DELETE\""))
        assertFailsWith<IllegalArgumentException> {
            repository.enqueueServiceEvent(
                id = "system-event-invalid",
                conversationId = "group-1",
                senderUserId = "system",
                clientTimestamp = 9_600L,
                event = ServiceMessagingV2Event(
                    action = "EDIT",
                    targetMessageId = "message-1",
                    content = "forged",
                    editedAt = 9_600L,
                ),
                recipientUserIds = setOf("bob"),
            )
        }
    }

    private fun groupCommand() = SendMessageV2Command(
        id = "message-1",
        conversationId = "group-1",
        senderUserId = "alice",
        senderDeviceId = 1,
        kind = "DATA",
        clientTimestamp = 9_000L,
        groupRevision = 7L,
        envelopes = listOf(
            OutboundEnvelope(DeviceTarget("bob", 1), "SENDER_KEY", "cipher-for-bob"),
            OutboundEnvelope(DeviceTarget("carol", 1), "SENDER_KEY", "cipher-for-carol"),
        ),
    )

    private fun seedBotParticipant() {
        transaction {
            Users.insert {
                it[id] = "bot_helper"
                it[name] = "helper"
                it[email] = "bot-helper@test.local"
                it[passwordHash] = "x"
                it[isOnline] = false
            }
            ChatParticipants.insert {
                it[chatId] = "group-1"
                it[userId] = "bot_helper"
                it[role] = "MEMBER"
                it[joinedAt] = 1L
            }
        }
    }

    private fun seedGroup() {
        val suffix = AtomicInteger().incrementAndGet()
        database = Database.connect(
            "jdbc:h2:mem:messaging-v2-$suffix-${kotlin.random.Random.nextInt()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        initDatabase()
        transaction {
            listOf("alice", "bob", "carol").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId-$suffix@test.local"
                    it[passwordHash] = "x"
                    it[isOnline] = false
                }
                SignalDevices.insert {
                    it[SignalDevices.userId] = userId
                    it[deviceId] = 1
                    it[deviceName] = "$userId phone"
                    it[status] = "CONFIRMED"
                    it[confirmedAt] = 1L
                    it[confirmedByDeviceId] = 1
                    it[createdAt] = 1L
                    it[lastSeenAt] = 1L
                }
                seedSignalBundle(userId, 1)
            }
            Chats.insert {
                it[id] = "group-1"
                it[isGroup] = true
                it[chatType] = "GROUP"
                it[groupName] = "group"
                it[memberRevision] = 7L
            }
            listOf("alice", "bob", "carol").forEach { userId ->
                ChatParticipants.insert {
                    it[chatId] = "group-1"
                    it[ChatParticipants.userId] = userId
                    it[role] = if (userId == "alice") "OWNER" else "MEMBER"
                    it[joinedAt] = 1L
                }
            }
        }
    }

    private fun seedSignalBundle(userId: String, deviceId: Int) {
        listOf(
            "identity_key" to null,
            "registration_id" to null,
            "signed_pre_key" to 1,
            "signed_pre_key_signature" to null,
        ).forEach { (keyType, keyId) ->
            SignalKeys.insert {
                it[id] = "$userId-$deviceId-$keyType"
                it[SignalKeys.userId] = userId
                it[SignalKeys.deviceId] = deviceId
                it[SignalKeys.keyType] = keyType
                it[keyData] = if (keyType == "registration_id") "1" else "test-key"
                it[SignalKeys.keyId] = keyId
                it[createdAt] = 1L
            }
        }
    }
}
