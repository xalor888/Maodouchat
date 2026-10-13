package com.maodouchat.server.repository

// Admin 身份导出：用户基础行、身份可发现性元数据、TOTP 状态（原 AdminExportIdentity 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminExportRepository.users(limit: Int): List<List<Any?>> = transaction {
    Users.selectAll()
        .orderBy(Users.lastSeen to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            listOf(
                row[Users.id],
                row[Users.name],
                row[Users.email],
                row[Users.status],
                row[Users.isOnline].toString(),
                row[Users.isModerator].toString(),
                row[Users.suspendedUntil].toString(),
                row[Users.lastSeen].toString(),
            )
        }
}

/** 身份可发现性元数据 only — no secrets / bodies；邮箱只给前 3 位 + `***`。 */
fun AdminExportRepository.identityUsers(limit: Int): List<List<Any?>> = transaction {
    Users.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[Users.id],
                row[Users.searchable].toString(),
                row[Users.showOnline].toString(),
                row[Users.totpEnabled].toString(),
                row[Users.email].take(3) + "***",
            )
        }
}

/** 只有 TOTP 状态 — no secrets / E2EE bodies。 */
fun AdminExportRepository.totpUsers(limit: Int): List<List<Any?>> = transaction {
    Users.selectAll()
        .where { Users.totpEnabled eq true }
        .limit(limit)
        .map { row ->
            listOf(
                row[Users.id],
                row[Users.totpEnabled].toString(),
                row[Users.email].take(3) + "***",
            )
        }
}
