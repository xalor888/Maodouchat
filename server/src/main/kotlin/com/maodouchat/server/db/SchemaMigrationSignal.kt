package com.maodouchat.server.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager



/** B03：补全 signal_keys.device_id 空值（遗留行）。 */
internal fun backfillSignalKeyDeviceIds() {
    TransactionManager.current().exec("UPDATE signal_keys SET device_id = 1 WHERE device_id IS NULL")
}



/** B03：补全 signal_devices.status / confirmed_at。 */
internal fun backfillSignalDeviceConfirmation() {
    val now = System.currentTimeMillis()
    TransactionManager.current().exec("UPDATE signal_devices SET status = 'CONFIRMED' WHERE status IS NULL OR status = ''")
    TransactionManager.current().exec("UPDATE signal_devices SET confirmed_at = $now WHERE status = 'CONFIRMED' AND confirmed_at IS NULL")
}


internal fun backfillMissingSignalDevices() {
    val now = System.currentTimeMillis()
    TransactionManager.current().exec(
        """
        INSERT INTO signal_devices (user_id, device_id, device_name, status, confirmed_at, created_at, last_seen_at)
        SELECT DISTINCT k.user_id, k.device_id,
               ('设备 #' || CAST(k.device_id AS VARCHAR(16))),
               'CONFIRMED', $now, $now, $now
        FROM signal_keys k
        WHERE NOT EXISTS (
            SELECT 1 FROM signal_devices d
            WHERE d.user_id = k.user_id AND d.device_id = k.device_id
        )
        """.trimIndent()
    )
}




/** B04：回填 direct_chat_pairs 映射，供后续删除 findLegacyDirectIdInTx 热路径兼容。 */
internal fun backfillDirectChatPairs() {
    val memberCounts = ChatParticipants.selectAll()
        .groupBy { it[ChatParticipants.chatId] }
        .filter { (_, rows) -> rows.size == 2 }
    if (memberCounts.isEmpty()) return
    val chatIds = memberCounts.keys
    val eligibleChats = Chats.selectAll().where { Chats.id inList chatIds }
        .filter { !it[Chats.isGroup] && it[Chats.chatType] != com.maodouchat.server.model.ChatType.SECRET }
    val now = System.currentTimeMillis()
    eligibleChats.forEach { chat ->
        val members = memberCounts.getValue(chat[Chats.id]).map { it[ChatParticipants.userId] }.sorted()
        val key = members.joinToString(":")
        val exists = DirectChatPairs.selectAll().where { DirectChatPairs.pairKey eq key }.any()
        if (!exists) {
            DirectChatPairs.insert {
                it[DirectChatPairs.pairKey] = key
                it[DirectChatPairs.chatId] = chat[Chats.id]
                it[DirectChatPairs.createdAt] = now
            }
        }
    }
}



/** B09：为既有库补充 signaling epoch / seq_no / idempotency_key 列（新库由基线建表自带）。 */
internal fun addSignalingEpochSequenceColumns() {
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS epoch BIGINT DEFAULT 0 NOT NULL"
    )
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS seq_no BIGINT DEFAULT 0 NOT NULL"
    )
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200) DEFAULT '' NOT NULL"
    )
}
