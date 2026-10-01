package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `exportChatInviteLink` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、
 * `sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`、
 * `sendChatAction` 之后**第四十九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/exportChatInviteLink`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；显式 JSON null 得字面量 `"null"`
 *   （`JsonNull` 是 `JsonPrimitive`，非空→`Ok`，逐字语义）；
 * - `rotate` 取 `obj["rotate"]?.jsonPrimitive?.booleanOrNull == true`——**严格判真**：
 *   JSON 字面量 `true` / 内容为 `"true"` 的字符串得 `true`——注意 `booleanOrNull`
 *   即 `content.toBooleanStrictOrNull()`，大小写**不**敏感（`"TRUE"`/`"True"`
 *   同样得 `true`；"严格"指非法输入得 `null` 而非大小写敏感）；缺席 / `false` /
 *   显式 null（`JsonNull` 的 `booleanOrNull` 为 null→`false`）/ 非真假字符串 /
 *   数字一律 `false`；
 *   对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]（大声失败，
 *   路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `expiresInSeconds` 取 `(obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
 *   ?: 604800).coerceIn(300, 2592000)`——缺席 / 非数字字符串 / 显式 JSON null
 *   （`"null"`→`toLongOrNull` 得 null）一律回默认 7 天；负数与 0 被夹到下限 300，
 *   超过 30 天被夹到上限 2592000（**先取缺省、后夹界**，逐字语义）；对象 / 数组型
 *   在 `?.jsonPrimitive` 处大声失败；
 * - `maxUses` 取 `(obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull()
 *   ?: 100).coerceIn(1, 1000)`——缺席 / 非数字字符串 / 显式 JSON null 一律回默认 100；
 *   小于 1 夹到 1，大于 1000 夹到 1000；对象 / 数组型大声失败；
 * - **合并必填**只有 `chatId`（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）——
 *   `rotate`/`expiresInSeconds`/`maxUses` 恒有默认值，从不判空（逐字语义）；
 * - 抽取顺序刻意与原处理器一致（`chatId`→`rotate`→`expiresInSeconds`→`maxUses`，
 *   最后判必填）——坏类型字段的抛错顺序也因此不变；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——`group_invites_disabled`
 *   开关门控、成员检查、频道拦截、`configureToken`/`logCommand` 与响应
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *   （开关门控在原处理器里**先于** body 解析，本轮重构保持门控在解析之前，
 *   等价；`expiresAt` 仍在处理器里按 `System.currentTimeMillis() + expiresIn * 1000L`
 *   计算，纯函数只返回收敛后的 `expiresInSeconds` 秒数。）
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotExportChatInviteLinkFields(
    val chatId: String,
    val rotate: Boolean,
    val expiresInSeconds: Long,
    val maxUses: Int,
)

internal sealed interface BotExportChatInviteLinkFieldsResult {
    data class Ok(val fields: BotExportChatInviteLinkFields) : BotExportChatInviteLinkFieldsResult
    data object MissingRequired : BotExportChatInviteLinkFieldsResult
}

/** 缺省与夹界常量（逐字取自原处理器，测试直接钉住同一份语义）。 */
internal const val BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS = 7L * 24 * 3600
internal const val BOT_EXPORT_CHAT_INVITE_MIN_EXPIRES_IN_SECONDS = 300L
internal const val BOT_EXPORT_CHAT_INVITE_MAX_EXPIRES_IN_SECONDS = 30L * 24 * 3600
internal const val BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES = 100
internal const val BOT_EXPORT_CHAT_INVITE_MIN_MAX_USES = 1
internal const val BOT_EXPORT_CHAT_INVITE_MAX_MAX_USES = 1000

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `rotate` → `expiresInSeconds` → `maxUses`，最后判 `chatId.isBlank()`。
 */
internal fun parseBotExportChatInviteLinkFields(obj: JsonObject): BotExportChatInviteLinkFieldsResult {
    // 注意：chatId 无 trim；rotate 严格判真（== true）；expiresInSeconds/maxUses
    // 先取缺省后夹界；只有 chatId 参与必填。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val rotate = obj["rotate"]?.jsonPrimitive?.booleanOrNull == true
    val expiresInSeconds = (obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_EXPIRES_IN_SECONDS)
        .coerceIn(BOT_EXPORT_CHAT_INVITE_MIN_EXPIRES_IN_SECONDS, BOT_EXPORT_CHAT_INVITE_MAX_EXPIRES_IN_SECONDS)
    val maxUses = (obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: BOT_EXPORT_CHAT_INVITE_DEFAULT_MAX_USES)
        .coerceIn(BOT_EXPORT_CHAT_INVITE_MIN_MAX_USES, BOT_EXPORT_CHAT_INVITE_MAX_MAX_USES)
    if (chatId.isBlank()) return BotExportChatInviteLinkFieldsResult.MissingRequired
    return BotExportChatInviteLinkFieldsResult.Ok(
        BotExportChatInviteLinkFields(chatId, rotate, expiresInSeconds, maxUses)
    )
}
