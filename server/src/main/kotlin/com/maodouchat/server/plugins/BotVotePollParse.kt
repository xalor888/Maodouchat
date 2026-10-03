package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `votePoll` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `echo` 之后**第七十四块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/votePoll`
 * 处理器里内联的**抽取 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `pollId` 取 `obj["pollId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；
 * - 显式 JSON null 不判空：`JsonNull` 是非空实例，`?.jsonPrimitive` 得自身→字面量
 *   `\"null\"`，非空，逐字语义；
 * - **选项索引**：`optionIndexes` 数组（存在且为数组型时）逐元素必须是
 *   非负整数——元素不是 `JsonPrimitive`（对象/数组型）、`.content.toIntOrNull()` 为 null
 *   （含 `JsonNull` 的字面量 `\"null\"`、小数 `\"1.5\"`）或负数，**整体拒绝**、不静默截成
 *   子集投票（原处理器 `// 9.157` 注释纪律，逐字搬移）；`optionIndexes` 缺席/为 null/
 *   非数组型（`as? JsonArray` 判 null）时回退看单字段 `optionIndex`（同样 `as? JsonPrimitive`
 *   → `.content?.toIntOrNull()`，缺/坏/负即 `Required`）；
 * - **错误文案与顺序逐字**：索引非法→400 `\"invalid optionIndexes\"`；
 *   `optionIndexes` 数组为空（存在但 `[]`，逐元素校验放行空列表）或 pollId 空白或
 *   单 `optionIndex` 缺/坏/负→400 `\"pollId/optionIndexes required\"`（原处理器两处
 *   同文案返回，纯函数合并为一种 `Required` 结果）；
 * - 抽取顺序：pollId 先、索引后——pollId 对象/数组型在 `?.jsonPrimitive` 处大声失败，
 *   先于索引校验抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500），与原处理器逐行一致；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流（`requireRateLimitedBot`）
 *   与 `isBotDeliverable` 在原处理器里**先于** body 解析，本轮保持它们在解析之前，
 *   等价；`vote`（投票）/`logCommand`（`\"votePoll\"`）/ 响应仍在处理器，顺序与原处理器
 *   一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotVotePollFields(
    val pollId: String,
    val optionIndexes: List<Int>,
)

internal sealed interface BotVotePollFieldsResult {
    data class Ok(val fields: BotVotePollFields) : BotVotePollFieldsResult
    /** 400 `\"invalid optionIndexes\"`：数组元素非法（非整数/负数/非原语）→整体拒绝。 */
    data object InvalidOptionIndexes : BotVotePollFieldsResult
    /** 400 `\"pollId/optionIndexes required\"` 的全部前件：原处理器两处同文案返回的合并。 */
    data object Required : BotVotePollFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * pollId（无 trim）+ optionIndexes/optionIndex（数组优先、非法整体拒绝）。
 */
internal fun parseBotVotePollFields(obj: JsonObject): BotVotePollFieldsResult {
    // 注意：pollId 无 trim；pollId 键显式 null → 字面量 "null"（不判空）。
    val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
    // 9.157：同用户投票端点——非法元素整体拒绝，不静默截成子集投票。
    val indexes = buildList {
        val arr = obj["optionIndexes"] as? JsonArray
        if (arr != null) {
            for (element in arr) {
                val v = (element as? JsonPrimitive)?.content?.toIntOrNull()
                if (v == null || v < 0) return BotVotePollFieldsResult.InvalidOptionIndexes
                add(v)
            }
        } else {
            val single = (obj["optionIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (single == null || single < 0) return BotVotePollFieldsResult.Required
            add(single)
        }
    }
    if (pollId.isBlank() || indexes.isEmpty()) return BotVotePollFieldsResult.Required
    return BotVotePollFieldsResult.Ok(BotVotePollFields(pollId, indexes))
}
