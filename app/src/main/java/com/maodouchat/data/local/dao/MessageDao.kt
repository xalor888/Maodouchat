package com.maodouchat.data.local.dao

import androidx.room.Dao

/**
 * B7 索引映射（v27→v28 追加，见 AppDatabase.MIGRATION_27_28；查询语句本身未改动）：
 * - [getMessagesByChatId] / [getRecentMessages] / [getFirstMessageAtOrAfter] /
 *   [getEarliestMessageTimestamp] → index_messages_chatId_timestamp (chatId, timestamp)
 * - [getExpiredMessageIds] → index_messages_expiresAt (expiresAt)
 * - [getImageMessages] / [getSearchableMessages] / [getSearchableMessageIds] /
 *   [getSearchableMessagesAfterCursor] → index_messages_type_timestamp (type, timestamp)
 */
@Dao
interface MessageDao : MessageTimelineDao, MessageContentSearchDao, MessageMutationDao, MessageExpiryDao {

}
