package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalingMessages
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * WebRTC 信令仓库
 *
 * 存储和转发 SDP Offer/Answer 和 ICE Candidate
 */
class SignalingRepository {

    /**
     * 存储信令消息
     */
    fun store(
        fromUserId: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ): String {
        return transaction {
            storeInTx(fromUserId, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite)
        }
    }

    /**
     * 终端信令（hang-up/busy/reject）与清理同 callId 旧信令放在同一事务：
     * 避免 store 已提交、clear 未执行的窗口里 offersOnly 只看到 hang-up 或只看到 offer。
     */
    fun storeTerminalAndClearOthers(
        fromUserId: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String,
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ): String {
        val normalizedType = type.lowercase()
        require(normalizedType in TERMINAL_SIGNAL_TYPES) { "not_terminal_signal:$type" }
        return transaction {
            val id = storeInTx(
                fromUserId,
                toUserId,
                normalizedType,
                payload,
                callId,
                groupId,
                groupMemberIds,
                groupInvite
            )
            if (callId.isNotBlank()) {
                clearForCallExcludingInTx(fromUserId, toUserId, callId, id)
            }
            id
        }
    }

    private fun storeInTx(
        fromUserId: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        groupInvite: Boolean
    ): String {
        purgeStaleInTx()
        val id = "sig_${UUID.randomUUID()}"
        SignalingMessages.insert {
            it[SignalingMessages.id] = id
            it[SignalingMessages.fromUserId] = fromUserId
            it[SignalingMessages.toUserId] = toUserId
            it[SignalingMessages.callId] = callId
            it[SignalingMessages.groupId] = groupId
            it[SignalingMessages.groupMemberIds] = groupMemberIds.joinToString(",")
            it[SignalingMessages.groupInvite] = groupInvite
            it[SignalingMessages.type] = type
            it[SignalingMessages.payload] = payload
            it[SignalingMessages.timestamp] = System.currentTimeMillis()
        }
        return id
    }

    /**
     * 获取并删除发给指定用户的信令消息
     * 使用 DELETE ... RETURNING 风格的原子操作，先获取 ID 再删除，避免并发重复消费
     *
     * offersOnly=true（冷启动 / IncomingCallWake）：
     *  除 offer 外，还必须一并返回这些 callId 上的终端信令（hang-up/busy/reject）。
     *  否则只拉到 offer 会重新弹来电 UI，而同 call 的挂断已在库中却被过滤掉，
     *  造成“幽灵来电 / 挂了还在响”。
     */
    fun consumeForUser(userId: String, offersOnly: Boolean = false): List<SignalingMessage> {
        return transaction {
            purgeStaleInTx()
            // FOR UPDATE 行锁确保并发事务串行化：
            // 第一个事务锁定并删除这些行后提交，第二个事务的 SELECT 不再看到已删除的行，
            // 从而避免两个并发轮询请求返回相同的信令消息（重复投递）。
            val messages = if (offersOnly) {
                // 必须拉取该用户全部终端信令，不能只匹配「仍在的 offer」：
                // hang-up 路径会 clear 同 callId 的 offer，库中只剩 hang-up。
                // 若 offersOnly 只返回 offer+同 call 终端，则孤儿 hang-up 永远不被消费，
                // 客户端仍可能凭 FCM 弹幽灵来电。
                // 单条有序查询统一锁定 offer/终端行，避免与全量消费形成锁序反转。
                SignalingMessages.selectAll().where {
                    val eligibleOffer =
                        (SignalingMessages.type eq "offer") and
                            ((SignalingMessages.groupId eq "") or (SignalingMessages.groupInvite eq true))
                    // 冷启动 / IncomingCallWake 轮询也必须一并返回 ice 候选：否则弱网/重连场景下
                    // 客户端只拉到 offer 没拉到 ice，信令在 TTL 后过期又无全量补偿，导致通话黑屏/连不上。
                    // 入库类型是 "ice-candidate"（与 ALLOWED_SIGNALING_TYPES 一致）；兼容历史 "ice"。
                    (SignalingMessages.toUserId eq userId) and
                        (eligibleOffer or (SignalingMessages.type inList TERMINAL_SIGNAL_TYPES) or (SignalingMessages.type inList ICE_SIGNAL_TYPES))
                }.orderBy(
                    SignalingMessages.timestamp to SortOrder.ASC,
                    SignalingMessages.id to SortOrder.ASC
                )
                    .forUpdate()
                    .map { it.toSignalingMessage() }
            } else {
                SignalingMessages.selectAll().where { SignalingMessages.toUserId eq userId }
                    .orderBy(
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

    private fun ResultRow.toSignalingMessage() = SignalingMessage(
        id = this[SignalingMessages.id],
        fromUserId = this[SignalingMessages.fromUserId],
        toUserId = this[SignalingMessages.toUserId],
        callId = this[SignalingMessages.callId],
        groupId = this[SignalingMessages.groupId],
        groupMemberIds = this[SignalingMessages.groupMemberIds].split(',').filter(String::isNotBlank),
        groupInvite = this[SignalingMessages.groupInvite],
        type = this[SignalingMessages.type],
        payload = this[SignalingMessages.payload],
        timestamp = this[SignalingMessages.timestamp]
    )

    companion object {
        /** 会结束通话振铃态的终端信令；offersOnly 轮询时必须与 offer 同批交付 */
        private val TERMINAL_SIGNAL_TYPES = listOf("hang-up", "busy", "reject")
        /** 与 Validation.ALLOWED_SIGNALING_TYPES 对齐；"ice" 仅兼容历史脏数据 */
        private val ICE_SIGNAL_TYPES = listOf("ice-candidate", "ice")
        /**
         * Offer/ICE older than this are unusable (client ring is 30s; coordinator STALE 120s).
         * Keep a modest server window so late poll still sees hang-up after a short offline period.
         */
        const val SIGNALING_TTL_MS = 150_000L
        /** 8.39：清理节流——store/consume 每次事务都全表 DELETE 会拉长写锁持有，改为最多每 60s 一次 */
        private const val PURGE_MIN_INTERVAL_MS = 60_000L
        private val lastPurgeAt = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private fun purgeStaleInTx() {
        val now = System.currentTimeMillis()
        val last = lastPurgeAt.get()
        if (now - last < PURGE_MIN_INTERVAL_MS) return
        if (!lastPurgeAt.compareAndSet(last, now)) return
        val cutoff = now - SIGNALING_TTL_MS
        SignalingMessages.deleteWhere { SignalingMessages.timestamp less cutoff }
    }

    private fun clearForCallExcludingInTx(userId1: String, userId2: String, callId: String, excludeId: String) {
        if (callId.isBlank()) return
        SignalingMessages.deleteWhere {
            val participantsMatch = (
                ((SignalingMessages.fromUserId eq userId1) and (SignalingMessages.toUserId eq userId2)) or
                ((SignalingMessages.fromUserId eq userId2) and (SignalingMessages.toUserId eq userId1))
            )
            participantsMatch and
                (SignalingMessages.callId eq callId) and
                (SignalingMessages.id neq excludeId)
        }
    }

    data class SignalingMessage(
        val id: String,
        val fromUserId: String,
        val toUserId: String,
        val callId: String,
        val groupId: String,
        val groupMemberIds: List<String>,
        val groupInvite: Boolean,
        val type: String,
        val payload: String,
        val timestamp: Long
    )
}
