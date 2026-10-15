package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom

// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

// SecureRandom 线程安全：每次生成 token 都 new 会反复播种，包级复用。
private val secureRandom = SecureRandom()

fun BotRepository.listByOwner(ownerUserId: String): List<BotDto> = transaction {
    BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }
        .orderBy(
            BotApps.createdAt to SortOrder.DESC,
            BotApps.id to SortOrder.DESC
        )
        .map { it.toDto() }
}

fun BotRepository.authenticate(token: String): BotDto? {
    val hash = hashToken(token.trim())
    return transaction {
        val row = BotApps.selectAll().where { (BotApps.tokenHash eq hash) and (BotApps.enabled eq true) }
            .firstOrNull() ?: return@transaction null
        // 认证源头顺带校验 owner 状态：封禁/注销、被禁发消息或动态的用户，其 bot 一律不可用
        //（bot 以自身身份收发，下游按 senderId 查不到 owner 的限制）。
        val owner = Users.selectAll().where { Users.id eq row[BotApps.ownerUserId] }.firstOrNull()
        if (owner == null || owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > System.currentTimeMillis()) {
            return@transaction null
        }
        val now = System.currentTimeMillis()
        if (owner[Users.messageRestrictedUntil] > now || owner[Users.postRestrictedUntil] > now) {
            return@transaction null
        }
        row.toDto()
    }
}

fun BotRepository.get(botId: String): BotDto? = transaction {
    BotApps.selectAll().where { BotApps.id eq botId }.firstOrNull()?.toDto()
}

internal fun BotRepository.normalizeUsername(raw: String): String? {
    val u = raw.trim().lowercase().removePrefix("@")
    if (!botUsernamePattern.matches(u)) return null
    return u
}

internal fun BotRepository.generateToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return "mdc_" + bytes.joinToString("") { "%02x".format(it) }
}

internal fun BotRepository.hashToken(token: String): String {
    val md = sha256ThreadLocal.get().apply { reset() }
    return md.digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
