package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `echo` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `deleteUpdates` 之后**第七十二块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotPresentationCardsRoutes` 的 `/api/bot/echo` 处理器里的**抽取**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `text` 有别名 `message`（`?:` 接在字段**存在性**上：`text` 键存在但为显式
 *   JSON null 时**不**穿透到 `message`，得字面量 `"null"`）；两键都缺 → `""`
 *   （缺省不 400——echo 只是原样回显，本来就没有必填校验）；
 * - 抽取链 `(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(500)`：
 *   `.take(500)` **不 trim**（原处理器逐字如此），前后空白计入 500 上限；
 * - 已知字段类型错（对象/数组型 `text`/`message`）时 `?.jsonPrimitive`
 *   抛 [IllegalArgumentException]（路由层 `StatusPages` 把它映射为 400「参数无效」，
 *   不是 500）；显式 JSON null 是 `JsonPrimitive` 子类型、`.content` 为字面量
 *   `"null"`——**不是类型错**（原处理器逐字如此，特意钉住）；
 * - 数字/布尔型走 `.content` 字符串（`"123"`/`"true"`，原处理器逐字如此）；
 * - 纯函数只做抽取：`obj` 为 null 的 `"invalid json"` 400 仍在处理器
 *   （与 `banChatMember`/`kickChatMember` 块同款纪律）；`logCommand`/
 *   `botId`/响应组装仍在处理器，下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotEchoFields(
    val text: String,
)

/**
 * 与原处理器逐字一致的 `echo` 字段抽取（`text`→`message` 别名链、take(500) 不 trim、
 * 无必填校验——故直接返回字段、无需 Result）。
 */
internal fun parseBotEchoFields(obj: JsonObject): BotEchoFields {
    // 注意：?: 接在字段存在性上——text 键存在（哪怕是显式 JSON null）就不穿透到
    // message，得字面量 "null"（原处理器逐字语义）；两键都缺才回 ""。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(500)
    return BotEchoFields(text)
}
