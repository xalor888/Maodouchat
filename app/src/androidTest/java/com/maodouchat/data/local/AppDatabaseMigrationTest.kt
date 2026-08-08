package com.maodouchat.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate16To17CreatesDraftTableAndPreservesChats() {
        helper.createDatabase(TEST_DB, 16).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, groupName, groupAnnouncement, memberRevision, participantIds
                ) VALUES ('c_migration', 'cipher-preview', 'TEXT', 1234, 2, 0, NULL, NULL, 0, 'u1,u2')
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DB,
            17,
            true,
            AppDatabase.MIGRATION_16_17
        )
        try {
            database.query("SELECT id, unreadCount FROM chats WHERE id = 'c_migration'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("c_migration", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
            }
            database.query("PRAGMA table_info(chat_drafts)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(setOf("ownerUserId", "chatId", "text", "updatedAt"), columns)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate17To18AddsConversationSettingsAndPreservesChats() {
        helper.createDatabase(SETTINGS_TEST_DB, 17).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, groupName, groupAnnouncement, memberRevision, participantIds
                ) VALUES ('c_settings', 'preview', 'TEXT', 99, 1, 0, NULL, NULL, 0, 'u1,u2')
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            SETTINGS_TEST_DB,
            18,
            true,
            AppDatabase.MIGRATION_17_18
        )
        try {
            database.query(
                "SELECT pinnedAt, notificationsMuted, archived, markedUnread, settingsUpdatedAt FROM chats WHERE id = 'c_settings'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(5) { index -> assertEquals(0L, cursor.getLong(index)) }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate18To19AddsMediaCenterIndex() {
        helper.createDatabase(MEDIA_INDEX_TEST_DB, 18).close()
        val database = helper.runMigrationsAndValidate(
            MEDIA_INDEX_TEST_DB,
            19,
            true,
            AppDatabase.MIGRATION_18_19
        )
        try {
            database.query("PRAGMA index_list(messages)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_messages_chatId_type_timestamp" in names)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate19To20AddsNullableGroupAvatar() {
        helper.createDatabase(GROUP_AVATAR_TEST_DB, 19).close()
        val database = helper.runMigrationsAndValidate(
            GROUP_AVATAR_TEST_DB,
            20,
            true,
            AppDatabase.MIGRATION_19_20
        )
        try {
            database.query("PRAGMA table_info(chats)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("groupAvatar" in columns)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate16To20RunsFullChainAndPreservesUserData() {
        helper.createDatabase(FULL_CHAIN_TEST_DB, 16).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, groupName, groupAnnouncement, memberRevision, participantIds
                ) VALUES ('c_full_chain', 'encrypted-preview', 'IMAGE', 7788, 4, 1, '迁移群', '公告', 9, 'u1,u2')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, chatId, senderId, content, type, timestamp, status,
                    editedAt, starred, reactionsJson
                ) VALUES ('m_full_chain', 'c_full_chain', 'u1', 'cipher-envelope', 'IMAGE', 7788, 'DELIVERED', NULL, 1, '[]')
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            FULL_CHAIN_TEST_DB,
            20,
            true,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20
        )
        try {
            database.query(
                """
                SELECT groupName, groupAnnouncement, memberRevision, pinnedAt,
                    notificationsMuted, archived, markedUnread, settingsUpdatedAt, groupAvatar
                FROM chats WHERE id = 'c_full_chain'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移群", cursor.getString(0))
                assertEquals("公告", cursor.getString(1))
                assertEquals(9L, cursor.getLong(2))
                repeat(5) { offset -> assertEquals(0L, cursor.getLong(3 + offset)) }
                assertTrue(cursor.isNull(8))
            }
            database.query("SELECT content, starred FROM messages WHERE id = 'm_full_chain'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cipher-envelope", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query("PRAGMA index_list(messages)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_messages_chatId_type_timestamp" in names)
            }
            database.query("PRAGMA table_info(chat_drafts)").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate25To26AddsSearchMessageTypeAndPreservesDocuments() {
        helper.createDatabase(SEARCH_TYPE_TEST_DB, 25).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_search_documents (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    searchableText TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    indexedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO message_search_documents (
                    messageId, chatId, senderId, searchableText, contentHash, timestamp, indexedAt
                ) VALUES ('m_s1', 'c_s1', 'u1', 'hello world', 'abc', 1000, 2000)
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            SEARCH_TYPE_TEST_DB,
            26,
            true,
            AppDatabase.MIGRATION_25_26
        )
        try {
            database.query("SELECT messageId, messageType FROM message_search_documents WHERE messageId = 'm_s1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("m_s1", cursor.getString(0))
                // 存量行默认 TEXT
                assertEquals("TEXT", cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate16To26RunsFullChainAndPreservesUserData() {
        helper.createDatabase(FULL_CHAIN_TEST_DB, 16).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, groupName, groupAnnouncement, memberRevision, participantIds
                ) VALUES ('c_full_chain', 'encrypted-preview', 'IMAGE', 7788, 4, 1, '迁移群', '公告', 9, 'u1,u2')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, chatId, senderId, content, type, timestamp, status,
                    editedAt, starred, reactionsJson
                ) VALUES ('m_full_chain', 'c_full_chain', 'u1', 'cipher-envelope', 'IMAGE', 7788, 'DELIVERED', NULL, 1, '[]')
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            FULL_CHAIN_TEST_DB,
            26,
            true,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26
        )
        try {
            database.query(
                """
                SELECT groupName, groupAnnouncement, memberRevision, pinnedAt,
                    notificationsMuted, archived, markedUnread, settingsUpdatedAt, groupAvatar
                FROM chats WHERE id = 'c_full_chain'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移群", cursor.getString(0))
                assertEquals("公告", cursor.getString(1))
                assertEquals(9L, cursor.getLong(2))
                repeat(5) { offset -> assertEquals(0L, cursor.getLong(3 + offset)) }
                assertTrue(cursor.isNull(8))
            }
            database.query("SELECT content, starred FROM messages WHERE id = 'm_full_chain'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cipher-envelope", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query("PRAGMA table_info(chat_drafts)").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            database.query("PRAGMA table_info(message_search_documents)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("messageType" in columns)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-16-17"
        const val SETTINGS_TEST_DB = "migration-17-18"
        const val MEDIA_INDEX_TEST_DB = "migration-18-19"
        const val GROUP_AVATAR_TEST_DB = "migration-19-20"
        const val SEARCH_TYPE_TEST_DB = "migration-25-26-search-type"
        const val FULL_CHAIN_TEST_DB = "migration-16-20-full-chain"
    }
}
