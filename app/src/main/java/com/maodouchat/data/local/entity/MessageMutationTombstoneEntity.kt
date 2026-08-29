package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "message_mutation_tombstones",
    primaryKeys = ["ownerUserId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"])],
)
data class MessageMutationTombstoneEntity(
    val ownerUserId: String,
    val messageId: String,
    val conversationId: String,
    val kind: String,
    val terminalAt: Long,
)

object MessageMutationTombstoneKind {
    const val DELETE = "DELETE"
    const val REVOKE = "REVOKE"
    const val CLEAR_HISTORY = "CLEAR_HISTORY"
}
