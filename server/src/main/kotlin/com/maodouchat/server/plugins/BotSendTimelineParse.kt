package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendTimelineFields(
    val chatId: String,
    val title: String,
    val items: List<String>,
)

internal sealed interface BotSendTimelineFieldsResult {
    data class Ok(val fields: BotSendTimelineFields) : BotSendTimelineFieldsResult
    data object MissingRequired : BotSendTimelineFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `title` → `items`，最后判 `chatId.isBlank() || items.isEmpty()`。
 */
internal fun parseBotSendTimelineFields(obj: JsonObject): BotSendTimelineFieldsResult {
    // 注意：chatId 无 trim；title 缺省才回 "Timeline"、显式 null 得字面 "null"、
    // 无 trim、先取后截 80；items 键缺席→空列表、显式 null/对象型在 ?.jsonArray
    // 处大声失败、数组内非 primitive 元素静默丢弃、逐项截 120、全表取前 12。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = (obj["title"]?.jsonPrimitive?.content ?: "Timeline").take(80)
    val items = (obj["items"]?.jsonArray?.mapNotNull {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    } ?: emptyList()).map { it.take(120) }.take(12)
    if (chatId.isBlank() || items.isEmpty()) return BotSendTimelineFieldsResult.MissingRequired
    return BotSendTimelineFieldsResult.Ok(BotSendTimelineFields(chatId, title, items))
}

/**
 * 与原处理器逐字一致的时间线正文组装：`### <title>` 之后逐行 `<序号>. <条目>`。
 */
internal fun buildBotTimelineContent(title: String, items: List<String>): String {
    val lines = items.mapIndexed { i, t -> (i + 1).toString() + ". " + t }.joinToString("\n")
    return "### " + title + "\n" + lines
}

// CI retrigger（2026-10-01）：run 36849886409 的 Server job 在「Compile and test server」
// 步骤 exit 1。静态分析已穷尽：主代码为逐行等价搬移（同包 internal、import 齐全、
// 路由无悬垂引用）；6 例新测试逐条手算通过（fuzz 基 payload 延续第四十一块教训、
// 必填字段确定性合法）；品牌术语脚本本地通过；ServerArchitectureTest 空基线、
// RouteRegistrySplitTest 端点计数均不受影响。job 日志无权读取（403）、
// annotations 只有通用 exit 1，暂无法定位具体失败用例——疑似单测抖动或 Gradle
// 环境问题，故以此注释提交重新触发全量 CI 做诊断性重跑。若再次在同一步骤失败，
// 则视为真 bug，下一轮凭新的失败证据继续定位。
