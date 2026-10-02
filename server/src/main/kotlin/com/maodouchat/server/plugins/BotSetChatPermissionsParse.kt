package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setChatPermissions` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `demoteChatMember` 之后**第六十五块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setChatPermissions`
 * 处理器里内联的**抽取 / 布尔与别名归一化 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空白；显式 JSON null 得字面量 `"null"`→非空→Ok（原处理器逐字如此）；
 * - `canSend` 取 camel `canSendMessages` 优先、snake `can_send_messages` 兜底的
 *   `?.jsonPrimitive?.booleanOrNull`——布尔语义是 kotlinx 的严格判定：JSON 布尔 true/false、
 *   或字符串字面量 `"true"`/`"false"`（仅小写）→真/假，其它（`"TRUE"`、数字、显式 null、
 *   空字符串等）→null→继续看别名；两键都给不出布尔→`Invalid`；
 * - `until` 取 camel `until` 优先、camel `untilDate` 兜底的
 *   `?.jsonPrimitive?.content?.toLongOrNull()`——数字或数字字符串→Long，非数字字符串/
 *   显式 null（`content`=`"null"`）→回落到别名/缺省 `0L`（下游 `muteUntil` 语义里 0=24 小时静音，
 *   本轮不动该语义，只逐字搬移）；
 * - **抽取先于必填校验**：`chatId`/`canSend`/`until` 三处 `?.jsonPrimitive` 都在
 *   `chatId.isBlank() || canSend == null` 判空之前执行——所以 `until` 取对象/数组型时，
 *   即使 chatId 空白也是先抛 [IllegalArgumentException]，而不是走 `Invalid`
 *   （本轮用测试钉住）；对象/数组型在 `?.jsonPrimitive` 处大声失败，路由层 `StatusPages`
 *   映射为 400「参数无效」，不是 500；
 * - **合并必填校验**：`chatId.isBlank() || canSend == null`→400
 *   `"chatId/canSendMessages required"`，文案逐字；
 * - 纯函数只负责抽取与校验，不碰仓库/限流/响应——限流在原处理器里**先于** body 解析，
 *   本轮保持它在解析之前，等价；成员检查（`isParticipant`）/批量静音/`logCommand`/
 *   修订通知/响应仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetChatPermissionsFields(
    val chatId: String,
    val canSend: Boolean,
    val until: Long,
)

internal sealed interface BotSetChatPermissionsFieldsResult {
    data class Ok(val fields: BotSetChatPermissionsFields) : BotSetChatPermissionsFieldsResult
    /** 400 `"chatId/canSendMessages required"` 的全部前件：chatId 缺/空/纯空白，或 canSend 非布尔。 */
    data object Invalid : BotSetChatPermissionsFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 布尔/别名归一化 + 合并必填校验。
 * 三字段端点：`chatId`（无 trim）+ `canSend`（camel 优先、snake 兜底、严格布尔）+
 * `until`（camel 优先、`untilDate` 兜底、缺省 0L）；
 * chatId 缺/空/纯空白，或 canSend 给不出布尔 → Invalid（处理器侧 400
 * `"chatId/canSendMessages required"`）。
 */
internal fun parseBotSetChatPermissionsFields(obj: JsonObject): BotSetChatPermissionsFieldsResult {
    // 注意：三处抽取都在必填判空之前；显式 null chatId → 字面量 "null"（不判空）；
    // 显式 null canSend → booleanOrNull 得 null → 继续看别名。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val canSend = obj["canSendMessages"]?.jsonPrimitive?.booleanOrNull
        ?: obj["can_send_messages"]?.jsonPrimitive?.booleanOrNull
    val until = obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0L
    if (chatId.isBlank() || canSend == null) return BotSetChatPermissionsFieldsResult.Invalid
    return BotSetChatPermissionsFieldsResult.Ok(BotSetChatPermissionsFields(chatId, canSend, until))
}
