package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.deleteMessagingV2ParticipantStateInTx
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.common.isAllowedWebhookAddress
import com.maodouchat.server.service.isUniqueViolation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

object BotRepository {
    // 8.48 修复 L1：收件箱事件 JSON 上限——超过即拒写（take 截断会从多字节字符/JSON token
    // 中间切断产生损坏行，bot 轮询解析抛异常）。正常事件远小于该值。
    internal const val MAX_UPDATE_JSON_CHARS = 16_000
    // 9.237：单 bot 收件箱积压上限（超限 FIFO 淘汰最旧，见 evictOldestInboxLocked）
    internal const val MAX_INBOX_PENDING_PER_BOT = 500L
    // 9.238：单 bot 命令日志上限（超限 FIFO 淘汰最旧，见 evictOldestCommandLogsLocked）
    internal const val MAX_COMMAND_LOGS_PER_BOT = 2_000L
    internal val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    internal val logger = org.slf4j.LoggerFactory.getLogger(BotRepository::class.java)
    internal val botUsernamePattern = Regex("^[a-z][a-z0-9_]{2,31}$")
    internal val botCommandPattern = Regex("^[a-z][a-z0-9_]{0,31}$")
    internal val ipv4HexLiteralPattern = Regex("""^0x[0-9a-f]+$""")
    internal val ipv4DigitsPattern = Regex("""\d+""")
    // SecureRandom 线程安全，每次生成 token 都 new 会反复播种，对象级复用。
    internal val secureRandom = SecureRandom()

    @Serializable
    data class BotDto(
        val id: String,
        val ownerUserId: String,
        val name: String,
        val username: String,
        val description: String? = null,
        val tokenPrefix: String,
        val webhookUrl: String? = null,
        val commandsJson: String? = null,
        val enabled: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val tokenOnce: String? = null // only returned on create/regenerate
    )

    @Serializable
    data class BotCommandDef(
        val command: String,
        val description: String
    )

    @Serializable
    data class ChatBotCommandItem(
        val botId: String,
        val username: String,
        val name: String,
        val command: String,
        val description: String
    )

    @Serializable
    data class ChatBotSummary(
        val id: String,
        val username: String,
        val name: String,
        val enabled: Boolean
    )

    sealed interface BotCreateResult {
        data class Success(val bot: BotDto) : BotCreateResult
        data object InvalidInput : BotCreateResult
        data object UsernameTaken : BotCreateResult
        data object MaxBotsReached : BotCreateResult
        data object OwnerInvalid : BotCreateResult
    }

    fun listByOwner(ownerUserId: String): List<BotDto> = transaction {
        BotApps.selectAll().where { BotApps.ownerUserId eq ownerUserId }
            .orderBy(
                BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
                BotApps.id to org.jetbrains.exposed.sql.SortOrder.DESC
            )
            .map { it.toDto() }
    }


    

    fun authenticate(token: String): BotDto? {
        val hash = hashToken(token.trim())
        return transaction {
            val row = BotApps.selectAll().where { (BotApps.tokenHash eq hash) and (BotApps.enabled eq true) }
                .firstOrNull() ?: return@transaction null
            // 8.33 安全修复 HIGH：bot 端点只校验 token 有效，封禁/注销用户的自有 bot 可继续
            // 收发消息（绕过 suspend「禁止发消息/禁止入群交互」）。在认证源头统一校验 owner 状态。
            val owner = Users.selectAll().where { Users.id eq row[BotApps.ownerUserId] }.firstOrNull()
            if (owner == null || owner[Users.deletedAt] != null || owner[Users.suspendedUntil] > System.currentTimeMillis()) {
                return@transaction null
            }
            // 8.37：消息/动态限制同样拦截——被禁发消息/禁发动态的账号不得借 bot 绕过
            // （bot 以自身身份收发，Sockets/REST 对 senderId 的限制查不到 owner 的行）
            val now = System.currentTimeMillis()
            if (owner[Users.messageRestrictedUntil] > now || owner[Users.postRestrictedUntil] > now) {
                return@transaction null
            }
            row.toDto()
        }
    }


    /**
     * 9.237：每 bot 积压封顶——停止轮询的 bot 不得让 bot_update_inbox 无限增长
     * （否则其所在群聊的消息洪泛可廉价放大 DB 写入）；超限按 FIFO 淘汰最旧，
     * 保留最近事件。须在事务内调用。
     */
    internal fun evictOldestInboxLocked(botId: String) {
        val pending = BotUpdateInbox.selectAll().where { BotUpdateInbox.botId eq botId }.count()
        if (pending < MAX_INBOX_PENDING_PER_BOT) return
        val overflow = (pending - MAX_INBOX_PENDING_PER_BOT + 1L).coerceAtLeast(1L).toInt()
        val oldestIds = BotUpdateInbox.selectAll()
            .where { BotUpdateInbox.botId eq botId }
            .orderBy(BotUpdateInbox.id to org.jetbrains.exposed.sql.SortOrder.ASC)
            .limit(overflow)
            .map { it[BotUpdateInbox.id] }
        if (oldestIds.isNotEmpty()) {
            BotUpdateInbox.deleteWhere { BotUpdateInbox.id inList oldestIds }
        }
    }


    /**
     * 清理超过保留期仍未消费的 bot 更新收件箱（默认 30 天）。
     * 正常 bot 消费后即删；bot 停用/失联时这里兜底，防止积压无限增长。
     * 由 Routing.kt 的周期清理循环调用。
     */
    fun purgeOldInbox(retentionDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotUpdateInbox.deleteWhere { BotUpdateInbox.createdAt less cutoff }
        }
    }


    /** 9.238：单 bot 命令日志封顶（FIFO 淘汰最旧）。须在事务内调用。 */
    internal fun evictOldestCommandLogsLocked(botId: String) {
        val total = BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }.count()
        if (total < MAX_COMMAND_LOGS_PER_BOT) return
        val overflow = (total - MAX_COMMAND_LOGS_PER_BOT + 1L).coerceAtLeast(1L).toInt()
        val oldestIds = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .orderBy(BotCommandLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.ASC)
            .limit(overflow)
            .map { it[BotCommandLogs.id] }
        if (oldestIds.isNotEmpty()) {
            BotCommandLogs.deleteWhere { BotCommandLogs.id inList oldestIds }
        }
    }


    data class CommandLogDto(
        val id: String,
        val botId: String,
        val chatId: String?,
        val userId: String?,
        val command: String,
        val createdAt: Long
    )


    fun get(botId: String): BotDto? = transaction {
        BotApps.selectAll().where { BotApps.id eq botId }.firstOrNull()?.toDto()
    }


    internal fun org.jetbrains.exposed.sql.ResultRow.toDto() = BotDto(
        id = this[BotApps.id],
        ownerUserId = this[BotApps.ownerUserId],
        name = this[BotApps.name],
        username = this[BotApps.username],
        description = this[BotApps.description],
        tokenPrefix = this[BotApps.tokenPrefix],
        webhookUrl = this[BotApps.webhookUrl],
        commandsJson = this[BotApps.commandsJson],
        enabled = this[BotApps.enabled],
        createdAt = this[BotApps.createdAt],
        updatedAt = this[BotApps.updatedAt]
    )

    internal fun normalizeUsername(raw: String): String? {
        val u = raw.trim().lowercase().removePrefix("@")
        if (!botUsernamePattern.matches(u)) return null
        return u
    }

    internal fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "mdc_" + bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun hashToken(token: String): String {
        val md = sha256ThreadLocal.get().apply { reset() }
        return md.digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }


    internal fun parseCommands(raw: String?): List<BotCommandDef> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val el = json.parseToJsonElement(raw)
            val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val command = obj["command"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }?.trim().orEmpty()
                val description = obj["description"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }?.trim().orEmpty()
                if (command.isBlank() || description.isBlank()) null
                else BotCommandDef(command = command, description = description)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }


    internal val DEFAULT_COMMANDS = listOf(
        BotCommandDef(command = "start", description = "开始使用"),
        BotCommandDef(command = "help", description = "帮助"),
    )

    internal fun encodeCommands(commands: List<BotCommandDef>): String {
        val arr = kotlinx.serialization.json.buildJsonArray {
            for (c in commands) {
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("command", kotlinx.serialization.json.JsonPrimitive(c.command))
                        put("description", kotlinx.serialization.json.JsonPrimitive(c.description))
                    }
                )
            }
        }
        return arr.toString()
    }

    internal fun containsCallbackData(content: String, callbackData: String): Boolean {
        val open = content.lastIndexOf("<meta>")
        val close = content.lastIndexOf("</meta>")
        if (open < 0 || close <= open || close + 7 != content.length) return false
        val raw = content.substring(open + 6, close)
        if (raw.length > MAX_CALLBACK_META_LENGTH) return false
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return false
        val rows = root["inlineKeyboard"] as? JsonArray ?: return false
        return rows.any { rowElement ->
            val row = rowElement as? JsonArray ?: return@any false
            row.any { buttonElement ->
                val button = buttonElement as? JsonObject ?: return@any false
                (button["callbackData"] as? JsonPrimitive)?.content == callbackData
            }
        }
    }

    internal fun isBoundedId(value: String): Boolean =
        value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }


    internal fun allowLoopbackWebhook(): Boolean =
        System.getenv("BOT_WEBHOOK_ALLOW_LOOPBACK")?.trim()?.equals("true", ignoreCase = true) == true

    internal const val MAX_CALLBACK_DATA_LENGTH = 128
    internal const val MAX_CALLBACK_META_LENGTH = 16_000

    internal fun isBlockedSsrfHost(host: String): Boolean {
        val h = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (h in setOf("metadata.google.internal", "169.254.169.254")) return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (isBlockedIpv4Literal(h)) return true
        if (h.contains(":")) {
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            if (h.startsWith("fc") || h.startsWith("fd")) return true
            if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return true
            val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
            return !address.isAllowedWebhookAddress(allowLoopback = false)
        }
        return false
    }

    internal fun isBlockedIpv4Literal(host: String): Boolean {
        if (host.matches(ipv4HexLiteralPattern)) return true
        val parts = host.split(".")
        if (parts.size !in 1..4) return false
        if (parts.any { it.isBlank() }) return true
        if (parts.any { it.startsWith("0x") || (it.length > 1 && it.startsWith("0")) }) return true
        if (parts.size != 4) {
            return parts.all { it.matches(ipv4DigitsPattern) }
        }
        val octets = parts.map { it.toIntOrNull() }
        if (octets.any { it == null || it !in 0..255 }) return true
        val a = octets[0]!!
        val b = octets[1]!!
        val c = octets[2]!!
        val d = octets[3]!!
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 168 && b == 63 && c == 129 && d == 16) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 198 && b in 18..19) ||
            (a == 192 && b == 0 && c in setOf(0, 2)) ||
            (a == 192 && b == 31 && c == 196) ||
            (a == 192 && b == 52 && c == 193) ||
            (a == 192 && b == 88 && c == 99) ||
            (a == 192 && b == 175 && c == 48) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113)
    }


    /**
     * 清理超过保留期的 bot 命令日志（默认 180 天），防止长期累积。
     * 由 Routing.kt 的周期清理循环调用；删 bot/删群时已按 botId/chatId 清理，
     * 这里兜底按时间清理孤立与历史行。
     */
    fun purgeOldCommandLogs(retentionDays: Int = 180): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotCommandLogs.deleteWhere { BotCommandLogs.createdAt less cutoff }
        }
    }
}
