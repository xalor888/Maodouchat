package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetMyCommandsFields(
    val commands: List<BotRepository.BotCommandDef>,
)

internal sealed interface BotSetMyCommandsFieldsResult {
    data class Ok(val fields: BotSetMyCommandsFields) : BotSetMyCommandsFieldsResult
    /** 400 `"commands array required"` 的全部前件：`commands` 缺席或非数组型。 */
    data object Invalid : BotSetMyCommandsFieldsResult
}

/**
 * 与原处理器逐字一致的 commands 数组抽取 + 逐项对象抽取 + 空白项丢弃。
 * `commands` 缺席 / 非数组型 → Invalid（处理器侧 400 `"commands array required"`）；
 * 否则逐项抽取，非对象项与空白项静默丢弃。
 */
internal fun parseBotSetMyCommandsFields(obj: JsonObject): BotSetMyCommandsFieldsResult {
    // 注意：此处是 as? 安全转型——缺席/错型不抛，直接 Invalid；
    // 项内 ?.jsonPrimitive 才是大声失败点（对象/数组型 command/description 先抛）。
    val arr = obj["commands"] as? JsonArray ?: return BotSetMyCommandsFieldsResult.Invalid
    val defs = arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val command = o["command"]?.jsonPrimitive?.content.orEmpty()
        val description = o["description"]?.jsonPrimitive?.content.orEmpty()
        if (command.isBlank() || description.isBlank()) null
        else BotRepository.BotCommandDef(command = command, description = description)
    }
    return BotSetMyCommandsFieldsResult.Ok(BotSetMyCommandsFields(defs))
}
