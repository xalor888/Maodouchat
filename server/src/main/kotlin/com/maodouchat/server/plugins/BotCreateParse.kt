package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `create` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `bot-callback` 之后**第七十九块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `POST /api/bots`
 * （`BotManagementRouting.kt`）处理器里内联的**三字段抽取**逻辑收敛为纯函数，
 * 行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `name`、`username` 均取 `obj["…"]?.jsonPrimitive?.content.orEmpty()`，
 *   **没有 `.trim()`**——前后空格原样保留，原处理器逐字如此；
 *   键缺席 → 空串（后续 `BotRepository.create` 自行判输入非法）；
 *   显式 JSON null 得字面量 `"null"`（非空→照常进入创建流程，逐字怪语义）；
 *   非字符串 primitive（`123` → `"123"`、`true` → `"true"`）走 `.content`
 *   原样通过，原处理器逐字如此；
 *   对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `description` 取 `obj["description"]?.jsonPrimitive?.content`——**可空**：
 *   键缺席 → `null`；显式 JSON null 得字面量 `"null"`（`JsonNull` 是
 *   `JsonPrimitive`，不是缺席，逐字怪语义）；对象 / 数组型同样大声失败；
 * - 纯函数只负责抽取，不碰仓库 / 限流 / 响应——维护模式
 *   （`rejectIfMaintenance`）、bot 平台开关（`isBotsAllowed`）、用户身份
 *   （`requireUserId`）、封禁拦截（`rejectIfSuspended`）、创建限流在原处理器里
 *   **先于** body 解析，本轮保持它们在解析之前，等价；
 *   `BotRepository.create` 的输入校验 / 用户名占用 / 数量上限 / 账号状态判定
 *   仍在处理器 `when` 分支里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotCreateFields(
    val name: String,
    val username: String,
    val description: String?,
)

/**
 * 与原处理器逐字一致的请求体抽取。
 * `name`/`username`（无 trim；缺席→空串；显式 null→字面量 `"null"`）+
 * `description`（无 trim；缺席→null；显式 null→字面量 `"null"`）。
 */
internal fun parseBotCreateFields(obj: JsonObject): BotCreateFields {
    // 注意：三字段均无 trim；缺席/显式 null 语义逐字；对象/数组型在
    // ?.jsonPrimitive 处大声失败。抽取顺序 name → username → description。
    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
    val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
    val description = obj["description"]?.jsonPrimitive?.content
    return BotCreateFields(name, username, description)
}
