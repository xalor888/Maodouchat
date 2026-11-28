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
            DatabaseMigrations.MIGRATION_16_17
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
            DatabaseMigrations.MIGRATION_17_18
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
            DatabaseMigrations.MIGRATION_18_19
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
            DatabaseMigrations.MIGRATION_19_20
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
            DatabaseMigrations.MIGRATION_16_17,
            DatabaseMigrations.MIGRATION_17_18,
            DatabaseMigrations.MIGRATION_18_19,
            DatabaseMigrations.MIGRATION_19_20
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
            DatabaseMigrations.MIGRATION_25_26
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
            DatabaseMigrations.MIGRATION_16_17,
            DatabaseMigrations.MIGRATION_17_18,
            DatabaseMigrations.MIGRATION_18_19,
            DatabaseMigrations.MIGRATION_19_20,
            DatabaseMigrations.MIGRATION_20_21,
            DatabaseMigrations.MIGRATION_21_22,
            DatabaseMigrations.MIGRATION_22_23,
            DatabaseMigrations.MIGRATION_23_24,
            DatabaseMigrations.MIGRATION_24_25,
            DatabaseMigrations.MIGRATION_25_26
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

    @Test
    fun migrate25To30RunsFullChainAndPreservesChatUsersAndSecretChats() {
        helper.createDatabase(FULL_CHAIN_25_TO_30_TEST_DB, 25).apply {
            execSQL(
                """
                INSERT INTO users (
                    id, name, email, isOnline, status, lastUpdated
                ) VALUES ('u_migration_30', '迁移用户', 'migration30@example.com', 0, '', 123)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, groupName, groupAnnouncement, memberRevision, participantIds,
                    pinnedAt, notificationsMuted, archived, markedUnread, settingsUpdatedAt, disappearingMessageSeconds
                ) VALUES ('c_migration_30', 'encrypted-preview', 'IMAGE', 7788, 4, 1, '迁移群', '公告', 9, 'u1,u2', 0, 0, 0, 0, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, chatId, senderId, content, type, timestamp, status,
                    editedAt, starred, reactionsJson
                ) VALUES ('m_migration_30', 'c_migration_30', 'u1', 'cipher-envelope', 'IMAGE', 7788, 'DELIVERED', NULL, 1, '[]')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO secret_chats (chatId, enabledAt)
                VALUES ('c_migration_30', 123)
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            FULL_CHAIN_25_TO_30_TEST_DB,
            30,
            true,
            DatabaseMigrations.MIGRATION_25_26,
            DatabaseMigrations.MIGRATION_26_27,
            DatabaseMigrations.MIGRATION_27_28,
            DatabaseMigrations.MIGRATION_28_29,
            DatabaseMigrations.MIGRATION_29_30
        )
        try {
            database.query("SELECT chatType FROM chats WHERE id = 'c_migration_30'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("GROUP", cursor.getString(0))
            }
            database.query("SELECT lastActivityAt FROM secret_chats WHERE chatId = 'c_migration_30'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            database.query("SELECT lastSeen FROM users WHERE id = 'u_migration_30'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            database.query("PRAGMA index_list(messages)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_messages_chatId_timestamp" in names)
                assertTrue("index_messages_expiresAt" in names)
            }
            database.query("PRAGMA index_list(chats)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_chats_archived_pinnedAt_lastMessageTime" in names)
            }
            database.query("SELECT content, starred FROM messages WHERE id = 'm_migration_30'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cipher-envelope", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate31To32MarksLegacySendingMessagesFailed() {
        helper.createDatabase(LEGACY_SENDING_TEST_DB, 31).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, chatType, groupName, groupAnnouncement, groupAvatar,
                    memberRevision, pinnedAt, notificationsMuted, archived, markedUnread,
                    settingsUpdatedAt, disappearingMessageSeconds, participantIds
                ) VALUES ('c_sending', '', 'TEXT', 0, 0, 0, 'DIRECT', NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 0, 'u1,u2')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, chatId, senderId, content, type, timestamp, status,
                    editedAt, starred, reactionsJson, expiresAt, sealedSender
                ) VALUES ('m_sending', 'c_sending', 'u1', 'legacy plaintext', 'TEXT', 55, 'SENDING', NULL, 0, '[]', NULL, 0)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            LEGACY_SENDING_TEST_DB,
            32,
            true,
            DatabaseMigrations.MIGRATION_31_32,
        )
        try {
            database.query("SELECT status FROM messages WHERE id = 'm_sending'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("FAILED", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM messaging_v2_outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate34To35AddsPlaintextJournalWithEmptyDefault() {
        helper.createDatabase(JOURNAL_TEST_DB, 34).apply {
            execSQL(
                """
                INSERT INTO messaging_v2_inbox (
                    envelopeId, ownerUserId, deviceId, sequence, messageId, conversationId,
                    senderUserId, senderDeviceId, kind, clientTimestamp, serverTimestamp,
                    ciphertextType, ciphertext, state, attempts, nextAttemptAt, receivedAt, updatedAt
                ) VALUES ('e_journal', 'u1', 1, 1, 'm_journal', 'c_journal', 'u2', 1, 'DATA', 1, 1, 'PREKEY', 'cipher', 'RECEIVED', 0, 0, 1, 1)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            JOURNAL_TEST_DB,
            35,
            true,
            DatabaseMigrations.MIGRATION_34_35,
        )
        try {
            // runMigrationsAndValidate above already asserts the Room schema (including the
            // column default) matches the entity; verify existing rows read back as empty.
            database.query("SELECT plaintextJournal FROM messaging_v2_inbox WHERE envelopeId = 'e_journal'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate33To34CreatesTerminalTombstonesWithConversationCascade() {
        helper.createDatabase(TOMBSTONE_TEST_DB, 33).apply {
            execSQL(
                """
                INSERT INTO chats (
                    id, lastMessage, lastMessageType, lastMessageTime, unreadCount,
                    isGroup, chatType, groupName, groupAnnouncement, groupAvatar,
                    memberRevision, pinnedAt, notificationsMuted, archived, markedUnread,
                    settingsUpdatedAt, disappearingMessageSeconds, participantIds
                ) VALUES ('c_terminal', '', 'TEXT', 0, 0, 0, 'DIRECT', NULL, NULL, NULL, 0, 0, 0, 0, 0, 0, 0, 'u1,u2')
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TOMBSTONE_TEST_DB,
            34,
            true,
            DatabaseMigrations.MIGRATION_33_34,
        )
        try {
            // MigrationTestHelper 返回的连接默认 **不** 启用外键（实测 PRAGMA foreign_keys=0），
            // 而 ON DELETE CASCADE 只有在外键强制开启时才生效。不显式打开，这条断言就退化成
            // "永远不级联、永远为 1"，而它此前从未被任何自动化执行过，所以一直没人发现。
            // 先打开，再断言前提成立——前提不成立时必须红，而不是静默通过。
            database.execSQL("PRAGMA foreign_keys = ON")
            val fkEnabled = database.query("PRAGMA foreign_keys").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
            assertEquals(
                "级联断言要求 SQLite 外键强制开启（PRAGMA foreign_keys）",
                1,
                fkEnabled,
            )

            database.execSQL(
                """
                INSERT INTO message_mutation_tombstones (
                    ownerUserId, messageId, conversationId, kind, terminalAt
                ) VALUES ('u1', 'm_terminal', 'c_terminal', 'DELETE', 123)
                """.trimIndent(),
            )
            database.query(
                "SELECT kind, terminalAt FROM message_mutation_tombstones WHERE messageId = 'm_terminal'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DELETE", cursor.getString(0))
                assertEquals(123L, cursor.getLong(1))
            }

            database.execSQL("DELETE FROM chats WHERE id = 'c_terminal'")
            database.query("SELECT COUNT(*) FROM message_mutation_tombstones").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate36To40CreatesBusinessTables() {
        helper.createDatabase(NEW_TABLES_TEST_DB, 36).close()

        val database = helper.runMigrationsAndValidate(
            NEW_TABLES_TEST_DB,
            40,
            true,
            DatabaseMigrations.MIGRATION_36_37,
            DatabaseMigrations.MIGRATION_37_38,
            DatabaseMigrations.MIGRATION_38_39,
            DatabaseMigrations.MIGRATION_39_40,
        )
        try {
            database.query("PRAGMA table_info(scheduled_messages)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.contains("idempotencyKey"))
            }
            database.query("PRAGMA table_info(archive_suggestion_dismissals)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(setOf("ownerUserId", "chatId", "dismissedAtMillis"), columns)
            }
            database.query("PRAGMA table_info(voice_played)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(setOf("ownerUserId", "messageId", "playedAtMillis"), columns)
            }
            database.query("PRAGMA table_info(notification_center_items)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(
                    setOf(
                        "ownerUserId", "id", "type", "mergeKey", "title", "subtitle",
                        "preview", "deeplink", "extraJson", "read", "count",
                        "createdAt", "updatedAt",
                    ),
                    columns,
                )
            }
            // 新表可写可读。
            database.execSQL(
                "INSERT INTO archive_suggestion_dismissals (ownerUserId, chatId, dismissedAtMillis) " +
                    "VALUES ('u1', 'c1', 1)"
            )
            database.query("SELECT COUNT(*) FROM archive_suggestion_dismissals WHERE ownerUserId = 'u1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    /**
     * v40→v41：新建 local_ai_providers / agent_sessions 两表。
     * 不用 runMigrationsAndValidate：41.json 由 Room 编译器在构建时生成
     * （exportSchema=true），本地无法构建、手写 identityHash 无法核验，故本次
     * 不提交手写版；这里直接跑迁移并用 PRAGMA 逐列核对建表结果（与实体定义一致），
     * 41.json 待收尾阶段从 CI 构建产物取回提交后再补 runMigrationsAndValidate。
     */
    @Test
    fun migrate40To41CreatesLocalAiTables() {
        val db = helper.createDatabase(LOCAL_AI_TEST_DB, 40)
        db.execSQL(
            "INSERT INTO notification_center_items " +
                "(ownerUserId, id, type, mergeKey, title, subtitle, preview, deeplink, " +
                "extraJson, read, count, createdAt, updatedAt) " +
                "VALUES ('u1', 'n1', 't', 'k', 'title', NULL, NULL, NULL, '{}', 0, 1, 1, 1)"
        )
        try {
            DatabaseMigrationsRecent.MIGRATION_40_41.migrate(db)

            assertTableColumns(
                db, "local_ai_providers",
                listOf(
                    Triple("ownerUserId", "TEXT", true),
                    Triple("id", "TEXT", true),
                    Triple("name", "TEXT", true),
                    Triple("baseUrl", "TEXT", true),
                    Triple("apiKey", "TEXT", true),
                    Triple("model", "TEXT", true),
                    Triple("protocol", "TEXT", true),
                    Triple("anthropicVersion", "TEXT", true),
                    Triple("organization", "TEXT", true),
                    Triple("extraHeadersJson", "TEXT", true),
                    Triple("temperature", "REAL", false),
                    Triple("topP", "REAL", false),
                    Triple("maxTokens", "INTEGER", true),
                    Triple("contextWindowTokens", "INTEGER", true),
                    Triple("historyMessageLimit", "INTEGER", true),
                    Triple("timeoutSeconds", "INTEGER", true),
                    Triple("stream", "INTEGER", true),
                    Triple("supportsVision", "INTEGER", true),
                    Triple("sortOrder", "INTEGER", true)
                ),
                primaryKey = listOf("ownerUserId", "id")
            )
            assertTableColumns(
                db, "agent_sessions",
                listOf(
                    Triple("ownerUserId", "TEXT", true),
                    Triple("id", "TEXT", true),
                    Triple("title", "TEXT", true),
                    Triple("createdAt", "INTEGER", true),
                    Triple("messagesJson", "TEXT", true),
                    Triple("sortOrder", "INTEGER", true)
                ),
                primaryKey = listOf("ownerUserId", "id")
            )
            assertTableIndex(db, "index_local_ai_providers_ownerUserId", "local_ai_providers", listOf("ownerUserId"))
            assertTableIndex(db, "index_agent_sessions_ownerUserId", "agent_sessions", listOf("ownerUserId"))

            // v40 数据保留。
            db.query("SELECT COUNT(*) FROM notification_center_items WHERE ownerUserId = 'u1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            // 新表可写可读（含可空 Double 列）。
            db.execSQL(
                "INSERT INTO local_ai_providers (ownerUserId, id, name, baseUrl, apiKey, model, " +
                    "protocol, anthropicVersion, organization, extraHeadersJson, temperature, topP, " +
                    "maxTokens, contextWindowTokens, historyMessageLimit, timeoutSeconds, stream, " +
                    "supportsVision, sortOrder) VALUES ('u1', 'p1', 'sf', 'https://x', 'sk', 'm', " +
                    "'OPENAI_CHAT_COMPLETIONS', '2023-06-01', '', '{}', 0.7, NULL, 4096, 128000, 24, 120, 1, 0, 0)"
            )
            db.query("SELECT apiKey, temperature, topP FROM local_ai_providers WHERE ownerUserId = 'u1' AND id = 'p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("sk", cursor.getString(0))
                assertEquals(0.7, cursor.getDouble(1), 0.0)
                assertTrue(cursor.isNull(2))
            }
            db.execSQL(
                "INSERT INTO agent_sessions (ownerUserId, id, title, createdAt, messagesJson, sortOrder) " +
                    "VALUES ('u1', 's1', 't', 7, '[{\"role\":\"user\",\"content\":\"hi\"}]', 0)"
            )
            db.query("SELECT title, messagesJson FROM agent_sessions WHERE ownerUserId = 'u1' AND id = 's1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("t", cursor.getString(0))
                assertTrue(cursor.getString(1).contains("\"content\":\"hi\""))
            }
        } finally {
            db.close()
        }
    }

    /** PRAGMA table_info 逐列核对：列名、声明类型、NOT NULL、复合主键位置。 */
    private fun assertTableColumns(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        expected: List<Triple<String, String, Boolean>>,
        primaryKey: List<String>,
    ) {
        val actual = buildList {
            db.query("PRAGMA table_info($table)").use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                        ) to cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    )
                }
            }
        }
        assertEquals(expected.map { it to if (it.first in primaryKey) primaryKey.indexOf(it.first) + 1 else 0 }, actual)
    }

    /** PRAGMA index_list + index_info 核对索引存在、非唯一、列一致。 */
    private fun assertTableIndex(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        indexName: String,
        table: String,
        columns: List<String>,
    ) {
        val found = buildList {
            db.query("PRAGMA index_list($table)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == indexName) {
                        add(cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 0)
                    }
                }
            }
        }
        assertEquals(listOf(true), found)
        val indexed = buildList {
            db.query("PRAGMA index_info($indexName)").use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }
        assertEquals(columns, indexed)
    }

    private companion object {
        const val TEST_DB = "migration-16-17"
        const val SETTINGS_TEST_DB = "migration-17-18"
        const val MEDIA_INDEX_TEST_DB = "migration-18-19"
        const val GROUP_AVATAR_TEST_DB = "migration-19-20"
        const val SEARCH_TYPE_TEST_DB = "migration-25-26-search-type"
        const val FULL_CHAIN_TEST_DB = "migration-16-20-full-chain"
        const val FULL_CHAIN_25_TO_30_TEST_DB = "migration-25-30-full-chain"
        const val LEGACY_SENDING_TEST_DB = "migration-31-32-legacy-sending"
        const val JOURNAL_TEST_DB = "migration-34-35-plaintext-journal"
        const val TOMBSTONE_TEST_DB = "migration-33-34-tombstone"
        const val NEW_TABLES_TEST_DB = "migration-36-40-business-tables"
        const val LOCAL_AI_TEST_DB = "migration-40-41-local-ai"
    }
}
