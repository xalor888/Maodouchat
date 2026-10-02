package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setMyDescription` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `setMyCommands` 之后**第六十七块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setMyDescription`
 * 处理器里内联的**简介抽取 / 别名回退**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - 键优先级：`obj["description"] ?: obj["about"]`——`description` 优先，`about` 回退；
 *   回退是**按存在而非按非空**：`description` 为空字符串时不回退（`""` 是非空实例，
 *   `?:` 不生效）；显式 JSON null 同样不回退（`JsonNull` 是非空实例）；
 * - 显式 JSON null 得字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，`?.jsonPrimitive`
 *   不抛，`.content` 得 `"null"`，原处理器逐字如此）；
 * - 双键缺席 → `null`（下游 `BotRepository.setMyDescription(botId, null)` 清简介，
 *   本轮不动该语义，只逐字搬移）；
 * - **无 trim / 无截断 / 无必填校验**：原处理器里抽取后直接进仓库，
 *   `trim()`/`take(500)` 在 `BotRepository.setMyDescription` 内部，本轮不搬；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取，不碰仓库 / 限流 / 响应——限流仍先于 body 解析
 *   （本轮重构保持它在解析之前，等价），`setMyDescription`/`logCommand`/响应仍在处理器，
 *   顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetMyDescriptionFields(
    val description: String?,
)

internal sealed interface BotSetMyDescriptionFieldsResult {
    data class Ok(val fields: BotSetMyDescriptionFields) : BotSetMyDescriptionFieldsResult
}

/**
 * 与原处理器逐字一致的简介抽取 + 别名回退。
 * 单字段端点：`description` 优先、`about` 回退（按存在而非按非空；显式 null 不回退→
 * 字面量 `"null"`）；双缺席 → `null`（下游清简介语义不变）。
 */
internal fun parseBotSetMyDescriptionFields(obj: JsonObject): BotSetMyDescriptionFieldsResult {
    // 注意：?: 回退只看存在性——空字符串 / 显式 null 的 description 都不回退到 about；
    // 显式 null 经 ?.jsonPrimitive（JsonNull 是 JsonPrimitive，不抛）得字面量 "null"。
    val description = (obj["description"] ?: obj["about"])?.jsonPrimitive?.content
    return BotSetMyDescriptionFieldsResult.Ok(BotSetMyDescriptionFields(description))
}
