package com.maodouchat.server.service

// outbox 持久化（落库/租约/终态/重放）：原 BotWebhookService 成员按域拆出的扩展函数（同包调用，零行为改动）。

import com.maodouchat.server.db.BotWebhookOutbox
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 同步落一条 PENDING outbox（先持久化再异步投递，进程退出不丢事件）。 */
internal fun BotWebhookService.insertOutbox(
    id: String, botId: String, url: String, tokenHash: String, body: String, ts: Long
) {
    val now = System.currentTimeMillis()
    transaction {
        BotWebhookOutbox.insert {
            it[BotWebhookOutbox.id] = id
            it[BotWebhookOutbox.botId] = botId
            it[BotWebhookOutbox.url] = url
            it[BotWebhookOutbox.tokenHash] = tokenHash
            it[BotWebhookOutbox.body] = body
            it[BotWebhookOutbox.ts] = ts
            it[BotWebhookOutbox.attempts] = 0
            it[BotWebhookOutbox.status] = "PENDING"
            it[BotWebhookOutbox.leaseOwner] = null
            it[BotWebhookOutbox.leaseUntil] = 0L
            it[BotWebhookOutbox.createdAt] = now
            it[BotWebhookOutbox.updatedAt] = now
        }
    }
}

/** 原子抢占租约；false 表示已终态或已被其他活跃 worker 认领。 */
internal fun BotWebhookService.claimLease(id: String, now: Long = System.currentTimeMillis()): Boolean =
    transaction {
        BotWebhookOutbox.update({
            (BotWebhookOutbox.id eq id) and
                (BotWebhookOutbox.status eq "PENDING") and
                ((BotWebhookOutbox.leaseUntil lessEq now) or BotWebhookOutbox.leaseOwner.isNull())
        }) {
            it[BotWebhookOutbox.leaseOwner] = workerId
            it[BotWebhookOutbox.leaseUntil] = now + WEBHOOK_LEASE_MS
            it[BotWebhookOutbox.updatedAt] = now
        }
    } > 0

/** 终态落库：仅当仍由本 worker 持有时才写，避免覆盖已被抢占的行。 */
internal fun BotWebhookService.finalizeOutbox(id: String, status: String, attempts: Int) {
    val now = System.currentTimeMillis()
    transaction {
        BotWebhookOutbox.update({
            (BotWebhookOutbox.id eq id) and (BotWebhookOutbox.leaseOwner eq workerId)
        }) {
            it[BotWebhookOutbox.status] = status
            it[BotWebhookOutbox.attempts] = attempts
            it[BotWebhookOutbox.leaseOwner] = null
            it[BotWebhookOutbox.leaseUntil] = 0L
            it[BotWebhookOutbox.updatedAt] = now
        }
    }
}

/**
 * B12：重放——认领所有“无活跃租约”的 PENDING outbox 重新投递。租约过期或未认领的
 * 行都会在 [deliverWebhook] 内以原子 UPDATE 抢占，多实例下同一行只会被一个 worker 拿走。
 */
internal fun BotWebhookService.replayStalePending() {
    val now = System.currentTimeMillis()
    val stale = transaction {
        BotWebhookOutbox.selectAll()
            .where {
                (BotWebhookOutbox.status eq "PENDING") and
                    ((BotWebhookOutbox.leaseUntil lessEq now) or BotWebhookOutbox.leaseOwner.isNull())
            }
            .map { row ->
                OutboxEntry(
                    id = row[BotWebhookOutbox.id],
                    botId = row[BotWebhookOutbox.botId],
                    url = row[BotWebhookOutbox.url],
                    tokenHash = row[BotWebhookOutbox.tokenHash],
                    body = row[BotWebhookOutbox.body],
                    ts = row[BotWebhookOutbox.ts],
                )
            }
    }
    stale.forEach { entry ->
        enqueue {
            deliverWebhook(entry.id, entry.url, entry.body, entry.ts, entry.tokenHash, entry.botId)
        }
    }
}
