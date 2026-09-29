package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendContact` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker` 之后**第九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotGeoRoutes` 的 `/api/bot/sendContact` 处理器里的**抽取 / 校验 /
 * 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括三处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - 别名链的「不穿透」语义：`name`→`firstName`、`phone`→`phoneNumber` 的 `?:`
 *   接在 **`jsonPrimitive` 之前**——主字段存在（哪怕显式 JSON null：`JsonNull`
 *   本就是 `JsonPrimitive` 的子类型，`.content` 为 `"null"` 字符串）即不穿透别名；
 *   于是显式 null 的 name 得到字面 `"null"`（`trim()` 不判住、非空），零行为改动；
 * - `userId` **没有 `trim()`**（`?.content?.take(64).orEmpty()`，与 name/phone 的
 *   `trim().take(80/40)` 不对称，原处理器逐字如此），钉住防「顺手修」；
 * - 必填是「`chatId` 非空 **且** (name / userId / phone 三者至少其一非空)」
 *   （`chatId and contact fields required`，400），纯函数里完整复刻。
 *
 * 校验顺序刻意与原处理器一致（功能门 `contact_card_enabled` 与成员检查在处理器里，
 * 纯函数只负责抽取与必填校验），不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendContactFields(
    val chatId: String,
    val contactName: String,
    val phone: String,
    val userId: String,
)

internal sealed interface BotSendContactFieldsResult {
    data class Ok(val fields: BotSendContactFields) : BotSendContactFieldsResult
    data object MissingRequired : BotSendContactFieldsResult
}

internal fun parseBotSendContactFields(obj: JsonObject): BotSendContactFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 jsonPrimitive 之前——主字段存在即不穿透别名（哪怕显式 null），原处理器逐字语义。
    val contactName = (obj["name"] ?: obj["firstName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
    val phone = (obj["phone"] ?: obj["phoneNumber"])?.jsonPrimitive?.content.orEmpty().trim().take(40)
    // 注意：userId 没有 trim()——与 name/phone 不对称，原处理器逐字如此。
    val userId = obj["userId"]?.jsonPrimitive?.content?.take(64).orEmpty()
    if (chatId.isBlank() || (contactName.isBlank() && userId.isBlank() && phone.isBlank())) {
        return BotSendContactFieldsResult.MissingRequired
    }
    return BotSendContactFieldsResult.Ok(BotSendContactFields(chatId, contactName, phone, userId))
}

/**
 * 与原处理器逐字一致的 CONTACT 消息内容组装。
 *
 * 首行 `"👤 "` 起手固定带尾空格：name 为空而 phone 非空时不补空格（`isNotEmpty() &&
 * !endsWith(" ")`），name 非空（已 trim，尾无空格）时补一个空格隔开 phone；
 * userId / phone 各自可选的 `\n[contactUser:]` / `\n[contactPhone:]` 标记行原样保留。
 */
internal fun buildBotContactContent(contactName: String, phone: String, userId: String): String =
    buildString {
        append("\uD83D\uDC64 ")
        if (contactName.isNotBlank()) append(contactName)
        if (phone.isNotBlank()) {
            if (isNotEmpty() && !endsWith(" ")) append(" ")
            append(phone)
        }
        if (userId.isNotBlank()) {
            append("\n[contactUser:")
            append(userId)
            append("]")
        }
        if (phone.isNotBlank()) {
            append("\n[contactPhone:")
            append(phone)
            append("]")
        }
    }
