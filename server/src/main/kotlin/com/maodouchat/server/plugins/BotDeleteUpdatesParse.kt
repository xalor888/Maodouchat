package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `deleteUpdates` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `setChatDescription` 之后**第六十九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/deleteUpdates`
 * 处理器里内联的**上限抽取 / 三层回退 / 必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `obj` 可为 `null`：body 不是 JSON 对象时 `runCatching { ...jsonObject }.getOrNull()`
 *   得 `null`；本端点**没有** `"invalid json"` 400（原处理器逐字如此）——null body
 *   直接走查询参数回退，给不出合法值则落到缺省 `0L`→`Invalid`（处理器侧 400
 *   `"upToId required"`，文案逐字）；
 * - 抽取优先级：body `upToId` 优先、body `offset` 兜底、query `upToId` 再兜底、
 *   缺省 `0L`；回退按「能否解析出 Long」而非按存在：
 *   `?.jsonPrimitive?.content?.toLongOrNull()`——JSON 数字或数字字符串→Long；
 *   非数字字符串 / 浮点数字符串（`"5.9"`→`toLongOrNull()` 得 `null`）/
 *   显式 JSON null（`JsonNull` 是 `JsonPrimitive`，不抛，`.content` 得字面量 `"null"`→
 *   `toLongOrNull()` 得 `null`）一律落空，继续看下一层回退；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500；
 *   抽取顺序 `upToId` 先——即使 `offset`/`query` 能给出合法值，`upToId` 取对象/
 *   数组型依然先抛，本轮用测试钉住）；
 * - **必填校验**：`upTo <= 0L`→400 `"upToId required"`（文案逐字；`0`/`-5`/非数字
 *   回落链全空均走这一条）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流（`requireRateLimitedBot`）
 *   在原处理器里**先于** body 解析，本轮保持它在解析之前，等价；
 *   `deleteUpdates`/`logCommand`/响应仍在处理器，顺序与原处理器一致，
 *   逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotDeleteUpdatesFields(
    val upTo: Long,
)

internal sealed interface BotDeleteUpdatesFieldsResult {
    data class Ok(val fields: BotDeleteUpdatesFields) : BotDeleteUpdatesFieldsResult
    /** 400 `"upToId required"` 的全部前件：body/query 回退链全空或全非法→缺省 `0L`，或解析出 `<= 0L`。 */
    data object Invalid : BotDeleteUpdatesFieldsResult
}

/**
 * 与原处理器逐字一致的上限抽取 + 三层回退 + 必填校验。
 * 单字段端点：body `upToId` 优先、body `offset` 兜底、query `upToId` 再兜底（`queryUpToId`
 * 即处理器传入的 `call.request.queryParameters["upToId"]`）、缺省 `0L`；
 * `obj` 为 null（body 非 JSON 对象）时直接走 query 回退；
 * 回退只看「能否解析出 Long」；`upTo <= 0L`→Invalid（处理器侧 400 `"upToId required"`）。
 */
internal fun parseBotDeleteUpdatesFields(
    obj: JsonObject?,
    queryUpToId: String?,
): BotDeleteUpdatesFieldsResult {
    // 注意：三层回退都在必填判空之前；显式 null upToId → 字面量 "null"→toLongOrNull
    // 得 null→继续回退；对象/数组型 upToId 即使后面键合法也先抛。
    val upTo = obj?.get("upToId")?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj?.get("offset")?.jsonPrimitive?.content?.toLongOrNull()
        ?: queryUpToId?.toLongOrNull()
        ?: 0L
    if (upTo <= 0L) return BotDeleteUpdatesFieldsResult.Invalid
    return BotDeleteUpdatesFieldsResult.Ok(BotDeleteUpdatesFields(upTo))
}
