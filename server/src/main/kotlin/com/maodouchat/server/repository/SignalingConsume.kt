package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalingMessages
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

// 取走并删除发给指定用户的信令（先取 ID 再删，避免并发重复消费）。
// offersOnly（冷启动/IncomingCallWake）：除 offer 外必须同批返回终端信令和 ice，
// 否则只拉到 offer 会弹幽灵来电、只拉到 offer 没拉到 ice 会黑屏/连不上。
fun SignalingRepository.consumeForUser(userId: String, offersOnly: Boolean = false): List<SignalingMessage> {
    return transaction {
        purgeStaleInTx()
        // FOR UPDATE 行锁让并发轮询串行化：先锁定的事务删行后提交，后到的 SELECT 不再看到已删行。
        val messages = if (offersOnly) {
            // 终端信令不能只匹配"仍在的 offer"：hang-up 路径会清掉同 call 的 offer，库里只剩 hang-up；
            // 若只返回 offer+同 call 终端，孤儿 hang-up 永远不被消费，客户端仍可能凭 FCM 弹幽灵来电。
            SignalingMessages.selectAll().where {
                val eligibleOffer =
                    (SignalingMessages.type eq "offer") and
                        ((SignalingMessages.groupId eq "") or (SignalingMessages.groupInvite eq true))
                // 入库类型是 "ice-candidate"；"ice" 仅兼容历史。
                (SignalingMessages.toUserId eq userId) and
                    (eligibleOffer or (SignalingMessages.type inList TERMINAL_SIGNAL_TYPES) or (SignalingMessages.type inList ICE_SIGNAL_TYPES))
            }.orderBy(
                SignalingMessages.epoch to SortOrder.ASC,
                SignalingMessages.sequence to SortOrder.ASC,
                SignalingMessages.timestamp to SortOrder.ASC,
                SignalingMessages.id to SortOrder.ASC
            )
                .forUpdate()
                .map { it.toSignalingMessage() }
        } else {
            SignalingMessages.selectAll().where { SignalingMessages.toUserId eq userId }
                .orderBy(
                    SignalingMessages.epoch to SortOrder.ASC,
                    SignalingMessages.sequence to SortOrder.ASC,
                    SignalingMessages.timestamp to SortOrder.ASC,
                    SignalingMessages.id to SortOrder.ASC
                )
                .forUpdate()
                .map { it.toSignalingMessage() }
        }
        if (messages.isNotEmpty()) {
            val ids = messages.map { it.id }
            SignalingMessages.deleteWhere { SignalingMessages.id inList ids }
        }
        messages
    }
}

internal fun ResultRow.toSignalingMessage() = SignalingMessage(
    id = this[SignalingMessages.id],
    fromUserId = this[SignalingMessages.fromUserId],
    toUserId = this[SignalingMessages.toUserId],
    callId = this[SignalingMessages.callId],
    groupId = this[SignalingMessages.groupId],
    groupMemberIds = this[SignalingMessages.groupMemberIds].split(',').filter(String::isNotBlank),
    groupInvite = this[SignalingMessages.groupInvite],
    type = this[SignalingMessages.type],
    payload = this[SignalingMessages.payload],
    epoch = this[SignalingMessages.epoch],
    sequence = this[SignalingMessages.sequence],
    idempotencyKey = this[SignalingMessages.idempotencyKey],
    timestamp = this[SignalingMessages.timestamp]
)
