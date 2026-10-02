package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `restrictChatMember` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `banChatMember` 之后**第六十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/restrictChatMember`
 * 处理器里内联的**抽取 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId`、`userId` 均取 `obj[...]?.jsonPrimitive?.content.orEmpty()`，**都没有
 *   `.trim()`**——全空白直接判空白，原处理器逐字如此（抽取顺序 chatId 先、userId 后）；
 * - 显式 JSON null 不判空：`JsonNull` 是非空实例，`?.jsonPrimitive` 得自身→字面量
 *   `"null"`，非空→`Ok`，逐字语义；
 * - **合并必填校验**：`chatId.isBlank() || userId.isBlank()`→400
 *   `"chatId/userId required"`，文案逐字；
 * - 可选的禁言截止 `until`：`untilDate` 优先、`mutedUntil` 兜底、缺省/非法均→`0L`。
 *   抽取式与原处理器逐字：`obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
 *   ?: obj["mutedUntil"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L`——
 *   显式 null（字面量 `"null"` 解析不成 Long）与缺席都滑向下一候选；**`until`
 *   的抽取发生在必填校验之前**，对象 / 数组型 `untilDate`/`mutedUntil` 在
 *   `?.jsonPrimitive` 处抛 [IllegalArgumentException]（大声失败，路由层
 *   `StatusPages` 映射为 400「参数无效」，不是 500）——哪怕 chatId 本来是空的；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流在原处理器里**先于** body 解析，
 *   本轮保持它在解析之前，等价；禁言时长上限钳制（8.33 修复，30 天上限）/成员检查
 *   （`isParticipant`）/`updateMemberMute`/`logCommand`/修订通知/响应仍在处理器里，
 *   顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotRestrictChatMemberFields(
    val chatId: String,
    val userId: String,
    val until: Long,
)

internal sealed interface BotRestrictChatMemberFieldsResult {
    data class Ok(val fields: BotRestrictChatMemberFields) : BotRestrictChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotRestrictChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段必填（`chatId` 无 trim、`userId` 无 trim；任一缺/空/纯空白 → Invalid，
 * 处理器侧 400 "chatId/userId required"）+ 可选 `until`（`untilDate`→`mutedUntil`→0L）。
 */
internal fun parseBotRestrictChatMemberFields(obj: JsonObject): BotRestrictChatMemberFieldsResult {
    // 注意：两字段均无 trim；任一键显式 null → 字面量 "null"（不判空）；抽取顺序 chatId 先、userId 后。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    // until 的抽取必须先于必填校验：坏类型 untilDate 在这里大声失败，与原处理器顺序逐字。
    val until = obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["mutedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0L
    if (chatId.isBlank() || userId.isBlank()) return BotRestrictChatMemberFieldsResult.Invalid
    return BotRestrictChatMemberFieldsResult.Ok(BotRestrictChatMemberFields(chatId, userId, until))
}
