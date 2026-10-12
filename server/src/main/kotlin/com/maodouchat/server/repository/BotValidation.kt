package com.maodouchat.server.repository

import com.maodouchat.server.common.isAllowedWebhookAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress

internal val botUsernamePattern = Regex("^[a-z][a-z0-9_]{2,31}$")
internal val botCommandPattern = Regex("^[a-z][a-z0-9_]{0,31}$")
internal val ipv4HexLiteralPattern = Regex("""^0x[0-9a-f]+$""")
internal val ipv4DigitsPattern = Regex("""\d+""")

internal val DEFAULT_COMMANDS = listOf(
    BotRepository.BotCommandDef(command = "start", description = "开始使用"),
    BotRepository.BotCommandDef(command = "help", description = "帮助"),
)

internal const val MAX_CALLBACK_DATA_LENGTH = 128
internal const val MAX_CALLBACK_META_LENGTH = 16_000

internal fun BotRepository.parseCommands(raw: String?): List<BotRepository.BotCommandDef> {
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
            else BotRepository.BotCommandDef(command = command, description = description)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun BotRepository.encodeCommands(commands: List<BotRepository.BotCommandDef>): String {
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

internal fun BotRepository.containsCallbackData(content: String, callbackData: String): Boolean {
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

internal fun BotRepository.isBoundedId(value: String): Boolean =
    value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

internal fun BotRepository.allowLoopbackWebhook(): Boolean =
    System.getenv("BOT_WEBHOOK_ALLOW_LOOPBACK")?.trim()?.equals("true", ignoreCase = true) == true

internal fun BotRepository.isBlockedSsrfHost(host: String): Boolean {
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

internal fun BotRepository.isBlockedIpv4Literal(host: String): Boolean {
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
