package com.maodouchat.server.service

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.AdminRankingResponse
import com.maodouchat.server.model.AdminStorageResponse
import com.maodouchat.server.model.OnlineUserAdminResponse
import com.maodouchat.server.model.RankingEntryResponse
import com.maodouchat.server.model.StorageBreakdownEntry
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction

fun OperationsQueryService.ranking(topN: Int): AdminRankingResponse = transaction {
    val msgCount = MessagingV2Messages.senderUserId.count()
    val topMessagers = (MessagingV2Messages innerJoin Users)
        .slice(MessagingV2Messages.senderUserId, Users.name, Users.avatar, msgCount)
        .selectAll()
        .where {
            Users.deletedAt.isNull() and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }
        .groupBy(MessagingV2Messages.senderUserId, Users.name, Users.avatar)
        .orderBy(msgCount to SortOrder.DESC)
        .limit(topN)
        .map {
            RankingEntryResponse(
                userId = it[MessagingV2Messages.senderUserId],
                userName = it[Users.name],
                avatar = it[Users.avatar],
                value = it[msgCount]
            )
        }
    val postCount = Posts.authorId.count()
    val topPosters = (Posts innerJoin Users)
        .slice(Posts.authorId, Users.name, Users.avatar, postCount)
        .selectAll()
        .where { Users.deletedAt.isNull() and (Posts.status eq "PUBLISHED") }
        .groupBy(Posts.authorId, Users.name, Users.avatar)
        .orderBy(postCount to SortOrder.DESC)
        .limit(topN)
        .map {
            RankingEntryResponse(
                userId = it[Posts.authorId],
                userName = it[Users.name],
                avatar = it[Users.avatar],
                value = it[postCount]
            )
        }
    val storageSum = EncryptedAttachments.cipherSize.sum()
    val topStorageUsers = (EncryptedAttachments innerJoin Users)
        .slice(EncryptedAttachments.uploaderId, Users.name, Users.avatar, storageSum)
        .selectAll()
        .where { Users.deletedAt.isNull() and (EncryptedAttachments.status eq "COMMITTED") }
        .groupBy(EncryptedAttachments.uploaderId, Users.name, Users.avatar)
        .orderBy(storageSum to SortOrder.DESC)
        .limit(topN)
        .map {
            RankingEntryResponse(
                userId = it[EncryptedAttachments.uploaderId],
                userName = it[Users.name],
                avatar = it[Users.avatar],
                value = it[storageSum] ?: 0L,
                detail = "bytes"
            )
        }
    val grpMsgCount = MessagingV2Messages.conversationId.count()
    val mostActiveGroups = (MessagingV2Messages innerJoin Chats)
        .slice(MessagingV2Messages.conversationId, Chats.groupName, grpMsgCount)
        .selectAll()
        .where {
            (Chats.isGroup eq true) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }
        .groupBy(MessagingV2Messages.conversationId, Chats.groupName)
        .orderBy(grpMsgCount to SortOrder.DESC)
        .limit(topN)
        .map {
            RankingEntryResponse(
                userId = it[MessagingV2Messages.conversationId],
                userName = it[Chats.groupName] ?: it[MessagingV2Messages.conversationId],
                avatar = null,
                value = it[grpMsgCount]
            )
        }
    AdminRankingResponse(topMessagers, topPosters, topStorageUsers, mostActiveGroups)
}
fun OperationsQueryService.storage(quotaBytes: Long): AdminStorageResponse = transaction {
    val allAttachments = EncryptedAttachments.selectAll()
        .where { EncryptedAttachments.status eq "COMMITTED" }
        .toList()
    val totalBytes = allAttachments.sumOf { it[EncryptedAttachments.cipherSize] }
    val totalFiles = allAttachments.size.toLong()
    val byMime = allAttachments.groupBy { att ->
        if (att[EncryptedAttachments.messageId] == null) "orphan" else "encrypted"
    }.map { (category, list) ->
        StorageBreakdownEntry(
            category = category,
            fileCount = list.size.toLong(),
            totalBytes = list.sumOf { it[EncryptedAttachments.cipherSize] }
        )
    }.sortedByDescending { it.totalBytes }
    val perUserBytes = allAttachments.groupBy { it[EncryptedAttachments.uploaderId] }
        .mapValues { it.value.sumOf { att -> att[EncryptedAttachments.cipherSize] } }
    val usersNearQuota = perUserBytes.count { it.value >= quotaBytes * 0.8 }
    AdminStorageResponse(
        totalBytes = totalBytes,
        totalFiles = totalFiles,
        byCategory = byMime,
        quotaPerUserBytes = quotaBytes,
        usersNearQuota = usersNearQuota.toLong()
    )
}
fun OperationsQueryService.onlineUsers(limit: Int): List<OnlineUserAdminResponse> = transaction {
    Users.selectAll().where {
        Users.deletedAt.isNull() and (Users.isOnline eq true)
    }.orderBy(Users.lastSeen to SortOrder.DESC).limit(limit).map {
        OnlineUserAdminResponse(
            id = it[Users.id],
            name = it[Users.name],
            email = it[Users.email],
            avatar = it[Users.avatar],
            lastSeen = it[Users.lastSeen],
            isModerator = it[Users.isModerator]
        )
    }
}
