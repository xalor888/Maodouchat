package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 投票路由（`POST /api/bot/sendPoll`、`POST /api/bot/sendDice`、
 * `POST /api/bot/votePoll`、`POST /api/bot/closePoll`，均在
 * `BotPollRouting.kt`）请求体 JSON 对象信封解析收敛为纯函数
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 poll 侧专项评估**第四块**）。
 *
 * 四个端点的信封抽取语义逐字相同，必须原样钉住：
 *
 * - `runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`：
 *   坏 JSON → 抛（吞掉）→ `null`；顶层非对象（数组 / 字符串 / 数字 /
 *   布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
 *   `.jsonObject` 处抛 `IllegalArgumentException`）→ 抛（吞掉）→ `null`；
 *   顶层为对象 → 原样返回该 `JsonObject`（不深拷贝、不排序、不过滤键——
 *   未知键全部保留，交由各自端点的字段抽取决定取舍）。
 * - 处理器侧 `?: return@post call.respond(HttpStatusCode.BadRequest,
 *   ErrorResponse("invalid json"))` 保持在处理器里，纯函数只负责解析、
 *   不碰响应——四个端点共用同一个「信封坏了」的判定，顺序与原处理器一致。
 * - 空 body：`receiveBoundedTextOrEmpty` 兜底的是 `""`（非 null），
 *   `Json.parseToJsonElement("")` 抛 → `null` → 400 `"invalid json"`，
 *   与原先逐字一致。
 * - 纯函数不收 `body` 上限（默认上限仍在各处理器）、不做鉴权/
 *   限流（`requireRateLimitedBot` 在信封解析之前）——下游一行不动
 *   （sendPoll 侧：`isBotDeliverable` + `isPollsEnabled` 在解析之前，
 *   `parseBotSendPollFields` 的「`options` 非数组判 `MissingRequired` /
 *   显式 null 选项保留字面量 `"null"` / `question` 不 trim /
 *   布尔别名穿透（`multi`→`allowsMultipleAnswers`、`anonymous`→`isAnonymous`）/
 *   `closesAt`→`closeDate` 穿透」；sendDice 侧：`parseBotSendDiceFields` 的
 *   「`emoji` 不 trim 全字串 `when` / `sides` 非数字穿透到 emoji 映射 /
 *   `coerceIn(2, 100)` 钳制」；votePoll 侧：`isBotDeliverable` 在解析之前，
 *   `parseBotVotePollFields` 的「`pollId` 无 trim / 显式 null 得字面量 `"null"` /
 *   `optionIndexes` 非法整体拒绝（9.157）/ 合并必填校验」；closePoll 侧：
 *   `isBotDeliverable` 在解析之前，`parseBotClosePollFields` 的「`pollId`
 *   无 trim / 显式 null 得字面量 `"null"` / 合并必填校验 400
 *   `"pollId required"`」，全部仍在处理器，顺序一致）。
 *
 * 评估结论沿用 G355：手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 坏 JSON 关口在解析前、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal fun parseBotPollJsonEnvelopeOrNull(body: String): JsonObject? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject 整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象）都得 null（吞掉），
    // 处理器再把 null 报成 400 "invalid json"——逐字怪语义。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
