package com.maodouchat.server.service

import com.maodouchat.server.db.JobLeases
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * 后台周期任务租约持有器（B01：双实例下同一任务同时只跑一份）。
 *
 * 基于 `job_leases` 行锁 + 条件写，PostgreSQL/H2 通用：
 * - 抢占：无行、已过期、或持有者是自己 → 成功；
 * - 续约：仅持有者可续，丢失返回 false（调用方应停跑）；
 * - 释放：仅持有者可删。
 * 接线（清理循环按任务抢租约）见后续步骤。
 */
class JobLease(
    val ownerId: String = "job-${UUID.randomUUID()}",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun tryAcquire(name: String, ttlMs: Long, now: Long = clock()): Boolean = transaction {
        val row = JobLeases.selectAll().where { JobLeases.name eq name }.forUpdate().firstOrNull()
        if (row == null) {
            // 并发双实例同时见到空行：恰好一个 INSERT 成功，败者吃唯一冲突返回 false。
            return@transaction runCatching {
                JobLeases.insert {
                    it[JobLeases.name] = name
                    it[owner] = ownerId
                    it[expiresAt] = now + ttlMs
                }
                true
            }.getOrElse { error ->
                if (com.maodouchat.server.repository.isUniqueViolation(error)) false else throw error
            }
        }
        if (row[JobLeases.owner] == ownerId || row[JobLeases.expiresAt] <= now) {
            JobLeases.update({ JobLeases.name eq name }) {
                it[owner] = ownerId
                it[expiresAt] = now + ttlMs
            }
            return@transaction true
        }
        false
    }

    fun heartbeat(name: String, ttlMs: Long, now: Long = clock()): Boolean = transaction {
        JobLeases.update({
            (JobLeases.name eq name) and (JobLeases.owner eq ownerId)
        }) {
            it[expiresAt] = now + ttlMs
        } > 0
    }

    fun release(name: String) {
        transaction {
            JobLeases.deleteWhere { (JobLeases.name eq name) and (JobLeases.owner eq ownerId) }
        }
    }

    fun ownerOf(name: String): String? = transaction {
        JobLeases.selectAll().where { JobLeases.name eq name }.firstOrNull()?.get(JobLeases.owner)
    }
}
