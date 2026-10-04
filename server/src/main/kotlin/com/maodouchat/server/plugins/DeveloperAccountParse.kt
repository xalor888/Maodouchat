package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// 开发者登录（/api/developer-account/login）请求体字段：
// 缺键/显式 null → ""；非原语值 ?.jsonPrimitive 大声失败，路由层 StatusPages 映射为 400。
internal data class DeveloperLoginFields(
    val email: String,
    val password: String,
    val totpCode: String?,
)

internal fun parseDeveloperLoginFields(obj: JsonObject): DeveloperLoginFields = DeveloperLoginFields(
    email = obj["email"]?.jsonPrimitive?.content.orEmpty(),
    password = obj["password"]?.jsonPrimitive?.content.orEmpty(),
    totpCode = obj["totpCode"]?.jsonPrimitive?.content,
)

// 开发者建 bot（POST /api/developer-account/bots）请求体字段：语义同上。
internal data class DeveloperBotCreateFields(
    val name: String,
    val username: String,
    val description: String?,
)

internal fun parseDeveloperBotCreateFields(obj: JsonObject): DeveloperBotCreateFields = DeveloperBotCreateFields(
    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
    username = obj["username"]?.jsonPrimitive?.content.orEmpty(),
    description = obj["description"]?.jsonPrimitive?.content,
)

// 开发者命令菜单（PUT /bots/{id}/commands）的 "commands" 数组抽取。
// 逐项严格：8.48 以来任一条目非法即 400（禁止静默丢弃后误清空菜单），
// 仅显式空数组 = 合法清空。Invalid.message 原样沿用路由层三条 400 文案。
internal sealed interface DeveloperBotCommandsParseResult {
    data class Ok(val defs: List<Pair<String, String>>) : DeveloperBotCommandsParseResult
    data class Invalid(val message: String) : DeveloperBotCommandsParseResult
}

internal fun parseDeveloperBotCommands(obj: JsonObject): DeveloperBotCommandsParseResult {
    val arr = obj["commands"] as? JsonArray ?: return DeveloperBotCommandsParseResult.Invalid("commands array required")
    val defs = buildList {
        for (item in arr) {
            val o = item as? JsonObject ?: return DeveloperBotCommandsParseResult.Invalid("invalid command item")
            val command = o["command"]?.jsonPrimitive?.content.orEmpty()
            val description = o["description"]?.jsonPrimitive?.content.orEmpty()
            if (command.isBlank() || description.isBlank()) {
                return DeveloperBotCommandsParseResult.Invalid("invalid command (command and description required)")
            }
            add(command to description)
        }
    }
    return DeveloperBotCommandsParseResult.Ok(defs)
}
