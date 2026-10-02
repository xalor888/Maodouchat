package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setMyCommands` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `setChatPermissions` 之后**第六十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setMyCommands`
 * 处理器里内联的 **commands 数组抽取 / 逐项对象抽取 / 空白项丢弃**逻辑收敛为纯函数，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `commands` 取 `obj["commands"] as? JsonArray`（**安全转型**，不是 `?.jsonPrimitive`）——
 *   缺席 / 非数组型（对象、字符串、数字、显式 null）一律得 null → `Invalid`
 *   （处理器侧 400 `"commands array required"`），**不抛**；
 * - 数组项 `item as? JsonObject` 也是安全转型——非对象项**静默丢弃**，不进 400；
 * - 项内 `command` / `description` 取 `o["command"]?.jsonPrimitive?.content.orEmpty()`——
 *   对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]（大声失败，
 *   路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；显式 JSON null 得字面量
 *   `"null"`（`JsonNull` 是 `JsonPrimitive`，非空→保留，原处理器逐字如此）；
 * - `command.isBlank() || description.isBlank()` 的项被丢弃（`mapNotNull`），**不是 400**——
 *   空数组 / 全丢弃 → `Ok(emptyList())`，下游 `normalizeCommands` 照常判定；
 * - 纯函数只负责抽取与项级过滤，不碰仓库 / 限流 / 响应——`normalizeCommands`
 *  （trim/小写/正则/去重/上限 100 的归一化校验，已是 `BotRepository` 里的独立纯函数，
 *   本轮不搬）、`setMyCommands` 落库、`logCommand` 与响应仍在处理器里，
 *   顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
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
