package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// Bot 应用、命令日志、webhook 发件箱与更新收件箱。

object BotApps : Table("bot_apps") {
    val id = varchar("id", 64)
    val ownerUserId = varchar("owner_user_id", 64).index()
    val name = varchar("name", 120)
    val username = varchar("username", 64).uniqueIndex()
    val description = text("description").nullable()
    val tokenHash = varchar("token_hash", 128)
    val tokenPrefix = varchar("token_prefix", 16)
    val webhookUrl = varchar("webhook_url", 500).nullable()
    val commandsJson = text("commands_json").nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}


object BotCommandLogs : Table("bot_command_logs") {
    val id = varchar("id", 64)
    val botId = varchar("bot_id", 64).index()
    val chatId = varchar("chat_id", 64).nullable()
    val userId = varchar("user_id", 64).nullable()
    val command = varchar("command", 120)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** B12：webhook 投递 outbox/死信（重启可重放 PENDING，失败入 DEAD 供审计/重放）。 */


object BotWebhookOutbox : Table("bot_webhook_outbox") {
    val id = varchar("id", 100)
    val botId = varchar("bot_id", 64).index()
    val url = varchar("url", 500)
    val tokenHash = varchar("token_hash", 128)
    val body = text("body")
    val ts = long("ts")
    val attempts = integer("attempts").default(0)
    val status = varchar("status", 20).default("PENDING").index()
    val leaseOwner = varchar("lease_owner", 100).nullable()
    val leaseUntil = long("lease_until").default(0L).index()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}


object BotUpdateInbox : Table("bot_update_inbox") {
    val id = long("id").autoIncrement()
    val botId = varchar("bot_id", 64).index()
    val updateJson = text("update_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
