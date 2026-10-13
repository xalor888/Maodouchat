package com.maodouchat.server.repository

// bot 命令解析簇：命令定义解析/编码与命名校验（原 BotValidation 扩展按域拆出，零行为改动）。

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val botUsernamePattern = Regex("^[a-z][a-z0-9_]{2,31}$")


internal val botCommandPattern = Regex("^[a-z][a-z0-9_]{0,31}$")


internal val DEFAULT_COMMANDS = listOf(
    BotRepository.BotCommandDef(command = "start", description = "开始使用"),
    BotRepository.BotCommandDef(command = "help", description = "帮助"),
)


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
