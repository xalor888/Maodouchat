package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot 回调转发请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `echo` 之后**第七十八块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把
 * `/api/chats/{chatId}/bot-callback`（`BotInteractionRouting.kt`）处理器里内联的
 * **抽取 / 合并必填与超长校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `messageId` / `botUserId` / `callbackData` 取
 *   `obj["…"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；
 *   显式 JSON null 得字面量 `"null"`（非空→通过必填，逐字怪语义）；
 *   非字符串 primitive（`123` → `"123"`、`true` → `"true"`）走 `.content`
 *   原样通过，原处理器逐字如此；
 *   对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - **合并校验**（`messageId.isBlank() || messageId.length > 80 ||
 *   botUserId.isBlank() || botUserId.length > 80 ||
 *   callbackData.isBlank() || callbackData.length > 128`→400
 *   `"messageId/botUserId/callbackData required"`，文案与上限逐字）——
 *   纯函数合并为一种 `Invalid` 结果；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——维护模式（`rejectIfMaintenance`）、
 *   用户身份（`requireUserId`）、封禁拦截（`rejectIfSuspended`）、成员校验
 *  （403 `"无权访问该聊天"`）在原处理器里**先于** body 解析，本轮保持它们在解析
 *   之前，等价；`BotRepository.get`（403 `"bot unavailable"`）/
 *   `enqueueCallbackIfAuthorized`（403 `"回调按钮无效或已不可用"`）/
 *   `BotWebhookService.notifyBotDirect` / 响应（`"ok": true`、`callbackQueryId`）
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotChatCallbackFields(
    val messageId: String,
    val botUserId: String,
    val callbackData: String,
)

internal sealed interface BotChatCallbackFieldsResult {
    data class Ok(val fields: BotChatCallbackFields) : BotChatCallbackFieldsResult
    /**
     * 400 `"messageId/botUserId/callbackData required"` 的全部前件：
     * 任一字段缺/空/纯空白，或 `messageId`/`botUserId` 超 80、`callbackData` 超 128。
     */
    data object Invalid : BotChatCallbackFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填与超长校验。
 * `messageId`/`botUserId`（无 trim；显式 null 得字面量 `"null"`；上限 80）、
 * `callbackData`（无 trim；显式 null 得字面量 `"null"`；上限 128）——
 * 任一缺/空/纯空白或超长 → Invalid（处理器侧 400
 * `"messageId/botUserId/callbackData required"`）。
 */
internal fun parseBotChatCallbackFields(obj: JsonObject): BotChatCallbackFieldsResult {
    // 注意：无 trim；三键显式 null → 字面量 "null"（不判空）；对象/数组型在
    // ?.jsonPrimitive 处大声失败。
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    val botUserId = obj["botUserId"]?.jsonPrimitive?.content.orEmpty()
    val callbackData = obj["callbackData"]?.jsonPrimitive?.content.orEmpty()
    if (messageId.isBlank() || messageId.length > 80 ||
        botUserId.isBlank() || botUserId.length > 80 ||
        callbackData.isBlank() || callbackData.length > 128
    ) {
        return BotChatCallbackFieldsResult.Invalid
    }
    return BotChatCallbackFieldsResult.Ok(BotChatCallbackFields(messageId, botUserId, callbackData))
}
