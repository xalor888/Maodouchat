package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setChatPhoto` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
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
 * `sendChatAction`、`exportChatInviteLink`、`revokeChatInviteLink`、
 * `unpinAllChatMessages` 之后**第五十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setChatPhoto`
 * 处理器里内联的**抽取 / 校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白 chatId 直接判空白，原处理器逐字如此；
 * - `base64` 取 `(obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()`，
 *   **没有 `.trim()`**；三键回退是**按「存在」回退、不是按「非空」回退**——
 *   `photoBase64` 在场但为空时不会回退到 `base64Data`，结果判必填缺失
 *   （`Elvis` 只跳过缺席键），原处理器逐字如此；
 * - 显式 JSON null 得字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，
 *   非空→`Ok`，逐字语义）；
 * - **合并必填**为 `chatId` + `base64`（任一 `.isBlank()`→400
 *   `"chatId/photoBase64 required"`，文案逐字——注意文案里写的是首选键名）；
 * - 对象 / 数组型 `chatId`、`base64` 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]（大声失败，
 *   路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 文件存储 / 响应——限流（原处理器里
 *   **先于** body 解析，本轮重构保持它在解析之前，等价）、成员检查、`saveGroupAvatar`、
 *   `updateAvatar`/`logCommand`/`notifyGroupRevisionChanged` 与响应仍在处理器里，
 *   顺序与原处理器一致，逐行等价——下游一行不动。
 *   （`saveGroupAvatar` 的 `IllegalArgumentException`→400「invalid photo」语义原样保留，
 *   与解析大声失败的 400「参数无效」是两个不同的 400，互不干扰。）
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetChatPhotoFields(
    val chatId: String,
    val base64: String,
)

internal sealed interface BotSetChatPhotoFieldsResult {
    data class Ok(val fields: BotSetChatPhotoFields) : BotSetChatPhotoFieldsResult
    data object MissingRequired : BotSetChatPhotoFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId` + `base64`（三键回退，无缺省/夹界逻辑）。
 */
internal fun parseBotSetChatPhotoFields(obj: JsonObject): BotSetChatPhotoFieldsResult {
    // 注意：两处均无 trim；按「存在」回退，photoBase64 在场但为空时不再回退。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val base64 = (obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || base64.isBlank()) return BotSetChatPhotoFieldsResult.MissingRequired
    return BotSetChatPhotoFieldsResult.Ok(BotSetChatPhotoFields(chatId, base64))
}
