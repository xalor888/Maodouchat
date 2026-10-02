package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setChatDescription` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `setMyDescription` 之后**第六十八块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setChatDescription`
 * 处理器里内联的**抽取 / 别名回退 + trim / 双 400 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空白；显式 JSON null 得字面量 `"null"`→非空→Ok（原处理器逐字如此）；
 * - `description` 取 `(obj["description"] ?: obj["announcement"])?.jsonPrimitive?.content.orEmpty().trim()`——
 *   `description` 优先、`announcement` 回退；回退是**按存在而非按非空**：
 *   `description` 为空字符串时不回退（`""` 是非空实例，`?:` 不生效）；
 *   显式 JSON null 同样不回退（`JsonNull` 是非空实例，`?.jsonPrimitive` 不抛，
 *   `.content` 得字面量 `"null"`，`.trim()` 后仍为 `"null"`→非空→Ok，原处理器逐字如此）；
 * - **trim 在长度夹界之前**：`description.length > 1200` 判的是 **trim 之后** 的值——
 *   1201 个空格 trim 后得 `""`→Ok（不判超长），原处理器逐字如此；
 * - **双 400 分开**：`chatId.isBlank()`→400 `"chatId required"`；
 *   `description.length > 1200`→400 `"description too long"`，文案逐字，
 *   因此结果三态（`Ok`/`Invalid`/`TooLong`），不合并；
 * - **抽取先于必填校验**：`chatId`/`description` 两处 `?.jsonPrimitive` 都在
 *   `chatId.isBlank()` 判空之前执行——所以 `description` 取对象/数组型时，
 *   即使 chatId 空白也是先抛 [IllegalArgumentException]，而不是走 `Invalid`
 *   （本轮用测试钉住）；对象/数组型在 `?.jsonPrimitive` 处大声失败，路由层 `StatusPages`
 *   映射为 400「参数无效」，不是 500；
 * - **空简介不清零语义不在纯函数里**：`description.takeIf { it.isNotBlank() }`（空→null，
 *   下游 `updateAnnouncement` 清公告）仍在处理器，本轮不动；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流在原处理器里**先于** body 解析，
 *   本轮保持它在解析之前，等价；成员检查（`isParticipant`）/`updateAnnouncement`/
 *   `logCommand`/修订通知/响应仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetChatDescriptionFields(
    val chatId: String,
    val description: String,
)

internal sealed interface BotSetChatDescriptionFieldsResult {
    data class Ok(val fields: BotSetChatDescriptionFields) : BotSetChatDescriptionFieldsResult
    /** 400 `"chatId required"` 的全部前件：chatId 缺/空/纯空白。 */
    data object Invalid : BotSetChatDescriptionFieldsResult
    /** 400 `"description too long"` 的全部前件：trim 后 description 超 1200 字符。 */
    data object TooLong : BotSetChatDescriptionFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 别名回退 + trim + 双 400 校验。
 * 双字段端点：`chatId`（无 trim）+ `description`（`description` 优先、`announcement` 回退、
 * 按存在而非按非空、显式 null 得字面量 `"null"`、trim 在长度夹界之前）；
 * chatId 缺/空/纯空白 → Invalid（处理器侧 400 `"chatId required"`）；
 * trim 后 description 超 1200 字符 → TooLong（处理器侧 400 `"description too long"`）。
 */
internal fun parseBotSetChatDescriptionFields(obj: JsonObject): BotSetChatDescriptionFieldsResult {
    // 注意：两处抽取都在必填判空之前；description 别名回退只看存在性——空字符串 /
    // 显式 null 的 description 都不回退到 announcement；显式 null 经 ?.jsonPrimitive
    //（JsonNull 是 JsonPrimitive，不抛）得字面量 "null"，trim 后仍非空。
    // trim 在长度校验之前；chatId 判空在长度校验之前，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val description = (obj["description"] ?: obj["announcement"])?.jsonPrimitive?.content.orEmpty().trim()
    if (chatId.isBlank()) return BotSetChatDescriptionFieldsResult.Invalid
    if (description.length > 1200) return BotSetChatDescriptionFieldsResult.TooLong
    return BotSetChatDescriptionFieldsResult.Ok(BotSetChatDescriptionFields(chatId, description))
}
