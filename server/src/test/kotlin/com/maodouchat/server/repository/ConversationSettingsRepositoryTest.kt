package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.initDatabase
import com.maodouchat.server.model.UpdateChatSettingsRequest
import com.maodouchat.server.service.DisappearingMessagePolicy
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationSettingsRepositoryTest {
    private var database: Database? = null

    private fun setupDb() {
        val url = "jdbc:h2:mem:conversation-settings-${COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
        database = Database.connect(url, driver = "org.h2.Driver", user = "sa", password = "")
        initDatabase()
        transaction {
            listOf("u1", "u2", "outsider").forEach { userId ->
                Users.insert {
                    it[id] = userId
                    it[name] = userId
                    it[email] = "$userId@test.local"
                    it[passwordHash] = "x"
                }
            }
        }
    }

    @AfterEach
    fun tearDownDb() {
        database?.let(TransactionManager::closeAndUnregister)
        database = null
    }

    @Test
    fun `outsider settings update persists nothing`() {
        setupDb()
        val chatId = ConversationCreationRepository().getOrCreateDirect("u1", "u2").id

        val outcome = ConversationSettingsRepository().updateUserSettings(
            chatId,
            "outsider",
            UpdateChatSettingsRequest(notificationsMuted = true),
        )

        assertEquals(ConversationSettingsMutationResult.NOT_PARTICIPANT, outcome.result)
        assertEquals(null, outcome.settings)
        transaction {
            assertEquals(0L, ChatUserSettings.selectAll().count())
        }
    }

    @Test
    fun `partial settings update preserves unspecified fields`() {
        setupDb()
        val chatId = ConversationCreationRepository().getOrCreateDirect("u1", "u2").id
        val repository = ConversationSettingsRepository()
        repository.updateUserSettings(
            chatId,
            "u1",
            UpdateChatSettingsRequest(notificationsMuted = true, archived = true),
        )

        val outcome = repository.updateUserSettings(
            chatId,
            "u1",
            UpdateChatSettingsRequest(pinned = true),
        )

        assertEquals(ConversationSettingsMutationResult.UPDATED, outcome.result)
        val settings = assertNotNull(outcome.settings)
        assertTrue(settings.pinnedAt > 0)
        assertTrue(settings.notificationsMuted)
        assertTrue(settings.archived)
        assertEquals(false, settings.markedUnread)
        transaction {
            val row = ChatUserSettings.selectAll().single()
            assertEquals(settings.pinnedAt, row[ChatUserSettings.pinnedAt])
            assertTrue(row[ChatUserSettings.notificationsMuted])
            assertTrue(row[ChatUserSettings.archived])
        }
    }

    @Test
    fun `group disappearing timer is rejected without mutation`() {
        setupDb()
        val chatId = ConversationCreationRepository().create(
            participantIds = listOf("u1", "u2"),
            isGroup = true,
            groupName = "group",
            creatorId = "u1",
        ).id

        val outcome = ConversationSettingsRepository().setDisappearingMessages(chatId, "u1", 60)

        assertEquals(ConversationSettingsMutationResult.GROUP_NOT_SUPPORTED, outcome.result)
        transaction {
            assertEquals(
                0,
                Chats.selectAll().where { Chats.id eq chatId }.single()[Chats.disappearingMessageSeconds],
            )
        }
    }

    @Test
    fun `secret timer always uses secure default`() {
        setupDb()
        val chatId = ConversationCreationRepository().getOrCreateSecret("u1", "u2").id
        transaction {
            Chats.update({ Chats.id eq chatId }) {
                it[disappearingMessageSeconds] = 0
            }
        }

        val outcome = ConversationSettingsRepository().setDisappearingMessages(chatId, "u2", 999)

        assertEquals(ConversationSettingsMutationResult.UPDATED, outcome.result)
        assertEquals(DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS, outcome.settings?.seconds)
        transaction {
            assertEquals(
                DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS,
                Chats.selectAll().where { Chats.id eq chatId }.single()[Chats.disappearingMessageSeconds],
            )
        }
    }

    @Test
    fun `direct invalid timer is rejected without normalization`() {
        setupDb()
        val chatId = ConversationCreationRepository().getOrCreateDirect("u1", "u2").id

        val outcome = ConversationSettingsRepository().setDisappearingMessages(chatId, "u1", 999)

        assertEquals(ConversationSettingsMutationResult.INVALID_TIMER, outcome.result)
        transaction {
            assertEquals(
                0,
                Chats.selectAll().where { Chats.id eq chatId }.single()[Chats.disappearingMessageSeconds],
            )
        }
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
