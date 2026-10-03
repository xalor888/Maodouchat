package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `stopPoll` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `echo` 之后**第七十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/stopPoll`
 * 处理器里内联的**抽取 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `pollId` 取 `obj[\"pollId\"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；
 * - 显式 JSON null 不判空：`JsonNull` 是非空实例，`?.jsonPrimitive` 得自身→字面量
 *   `\\\"null\\\"`，非空→`Ok`，逐字语义；
 * - **合并必填校验**：`pollId.isBlank()`→400 `\\\"pollId required\\\"`，文案逐字；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流（`requireRateLimitedBot`）
 *   与 `isBotDeliverable` 在原处理器里**先于** body 解析，本轮保持它们在解析之前，
 *   等价；`closePoll`（仅创建者可停，仓库调用与 `closePoll` 端点同为
 *   `requireBotDeliverable = true`）/ `logCommand`（`\"stopPoll\"`）/ 响应
 *   （含逐字保留的 `alias: \"closePoll\"` 兼容字段）仍在处理器里，顺序与原处理器一致，
 *   逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotStopPollFields(
    val pollId: String,
)

internal sealed interface BotStopPollFieldsResult {
    data class Ok(val fields: BotStopPollFields) : BotStopPollFieldsResult
    /** 400 `\\\"pollId required\\\"` 的全部前件：pollId 缺/空/纯空白。 */
    data object Invalid : BotStopPollFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 单字段端点：`pollId`（无 trim）；缺/空/纯空白 → Invalid
 * （处理器侧 400 \"pollId required\"）。
 */
internal fun parseBotStopPollFields(obj: JsonObject): BotStopPollFieldsResult {
    // 注意：无 trim；pollId 键显式 null → 字面量 \"null\"（不判空）。
    val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
    if (pollId.isBlank()) return BotStopPollFieldsResult.Invalid
    return BotStopPollFieldsResult.Ok(BotStopPollFields(pollId))
}
