package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 管理后台用户管理子域路由（`POST /users/{id}/sessions/revoke`、
 * `POST /broadcast`，均在 `AdminManagementRouting.kt`）
 * 请求体 JSON 对象信封解析收敛为纯函数（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 admin 侧专项评估**第二块**）。
 *
 * 两端点的信封抽取语义逐字相同，必须原样钉住：
 *
 * - `runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`：
 *   坏 JSON → 抛（吞掉）→ `null`；顶层非对象（数组 / 字符串 / 数字 /
 *   布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
 *   `.jsonObject` 处抛 `IllegalArgumentException`）→ 抛（吞掉）→ `null`；
 *   顶层为对象 → 原样返回该 `JsonObject`（不深拷贝、不排序、不过滤键——
 *   未知键全部保留，交由各自端点的字段抽取决定取舍）。
 * - `POST /users/{id}/sessions/revoke` 的「空 body 宽容」语义保留在处理器：
 *   body 读取 `call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS)` 失败
 *   （超限/不可读）→ 400 `"request body is too large or unreadable"`，
 *   与其余两处（兜底空串再由解析丢出 `null` → 400 `"invalid json"`）的
 *   body 读取怪语义互不影响，纯函数只换内联表达式不挪读写的位子；
 *   空 body → 处理器 `if (body.isBlank()) null`（对象为 null，下游
 *   `obj?.get(...)` 走缺省：`tokenHashPrefix=""`、`all=false`，
 *   随后由「tokenHashPrefix 或 all=true 必选」判定 400）；
 *   非空坏 JSON → 纯函数返回 null → 处理器原 400 `"invalid json"`。
 * - 处理器侧 `?: return@post call.respond(HttpStatusCode.BadRequest,
 *   ErrorResponse("invalid json"))` 保持在处理器里，纯函数只负责解析、
 *   不碰响应——两端点共用同一个「信封坏了」的判定，顺序与原处理器一致。
 * - 纯函数不做鉴权（`call.isAdminUser()` 在信封解析之前）——下游一行不动
 *   （sessions/revoke 侧：`tokenHashPrefix` 必须是 12-64 位十六进制
 *   字符串（`JsonPrimitive` 字串 + `.trim()`）、`all` 必须是严格 JSON
 *   布尔（字符串 `"true"`/`"false"` 一律拒绝；`booleanOrNull` 的宽松
 *   字符串解析是明面上的陷阱，测试不碰、语义原样）、二者互斥与至少一者
 *   必选、单个前缀吊销与全量吊销两条路径；broadcast 侧：`text`
 *   `trim().take(2000)` 必填、`title` `trim().take(120)` 空回退 `"System"`、
 *   `ADMIN_BROADCAST` 全员扇出（best-effort）+ 审计，全部仍在处理器，
 *   顺序一致）。
 * - 同一文件 `PUT /users/{id}/moderator` 的内联字段级解析
 *   （`Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive`
 *   再 `booleanOrNull`）不是信封抽取，本块不动它。
 *
 * 评估结论：手写解析本来就满足 fuzz 系列的契约（未知键忽略、坏 JSON 关口在
 * 解析前、无未处理 500），这里只是把它变成可被测试钉住的形态，零行为改动。
 */
internal fun parseAdminManagementJsonEnvelopeOrNull(body: String): JsonObject? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject 整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象）都得 null（吞掉），
    // 处理器再把 null 报成 400 "invalid json"——逐字怪语义。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
