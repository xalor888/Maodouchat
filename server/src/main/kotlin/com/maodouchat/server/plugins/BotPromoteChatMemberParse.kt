package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `promoteChatMember` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `unbanChatMember` 之后**第六十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/promoteChatMember`
 * 处理器里内联的**抽取 / 角色归一化 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId`、`userId` 均取 `obj[...]?.jsonPrimitive?.content.orEmpty()`，**都没有
 *   `.trim()`**——全空白直接判空白，原处理器逐字如此（抽取顺序 chatId 先、userId 后、role 末）；
 * - `role` 归一化逐字：`obj["role"]?.jsonPrimitive?.content.orEmpty().uppercase().ifBlank { "ADMIN" }`
 *   再 `if (roleRaw == "MEMBER") "MEMBER" else "ADMIN"`——`role` 缺席/空/纯空白→`"ADMIN"`；
 *   大小写不敏感的 `"MEMBER"`（靠 `.uppercase()`）→`"MEMBER"`；其它任意值（`"OWNER"`、
 *   `"admin"`、带空白的 `" member "`——role 无 trim、比较是精确相等、非 `"MEMBER"` 全滑向
 *   `"ADMIN"`；显式 JSON null→字面量 `"null"`.uppercase()=`"NULL"`→`"ADMIN"`）；
 * - **抽取先于必填校验**：原处理器里 `role` 的 `?.jsonPrimitive` 在空白判空**之前**执行——
 *   所以 `role` 取对象 / 数组型时，即使 chatId/userId 为空也是先抛 [IllegalArgumentException]，
 *   而不是走 `Invalid`（本轮用测试钉住）；
 * - **合并必填校验**：`chatId.isBlank() || userId.isBlank()`→400
 *   `"chatId/userId required"`，文案逐字；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流在原处理器里**先于** body 解析，
 *   本轮保持它在解析之前，等价；成员检查（`isParticipant`）/
 *   `groupMembershipService.updateRole` / `logCommand` / 修订通知 / 响应
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotPromoteChatMemberFields(
    val chatId: String,
    val userId: String,
    val role: String,
)

internal sealed interface BotPromoteChatMemberFieldsResult {
    data class Ok(val fields: BotPromoteChatMemberFields) : BotPromoteChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotPromoteChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 角色归一化 + 合并必填校验。
 * 三字段端点：`chatId`（无 trim）+ `userId`（无 trim）+ `role`（无 trim、上大写后精确判 `"MEMBER"`）；
 * 任一 chatId/userId 缺/空/纯空白 → Invalid（处理器侧 400 "chatId/userId required"）。
 */
internal fun parseBotPromoteChatMemberFields(obj: JsonObject): BotPromoteChatMemberFieldsResult {
    // 注意：role 的抽取与归一化在空白判空之前；显式 null → 字面量 "null"（不判空）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    val roleRaw = obj["role"]?.jsonPrimitive?.content.orEmpty().uppercase().ifBlank { "ADMIN" }
    val role = if (roleRaw == "MEMBER") "MEMBER" else "ADMIN"
    if (chatId.isBlank() || userId.isBlank()) return BotPromoteChatMemberFieldsResult.Invalid
    return BotPromoteChatMemberFieldsResult.Ok(BotPromoteChatMemberFields(chatId, userId, role))
}
