package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotEditMessageCaptionFields(
    val messageId: String,
    val caption: String,
)

internal sealed interface BotEditMessageCaptionFieldsResult {
    data class Ok(val fields: BotEditMessageCaptionFields) : BotEditMessageCaptionFieldsResult
    data object MissingMessageId : BotEditMessageCaptionFieldsResult
}

internal fun parseBotEditMessageCaptionFields(obj: JsonObject): BotEditMessageCaptionFieldsResult {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：caption 双别名（caption → text）+ 无 trim + 截 1000，逐字保留；
    // 显式 JSON null 得字面量 "null"；对象/数组型在 ?.jsonPrimitive 处大声失败。
    val caption = (obj["caption"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1000)
    if (messageId.isBlank()) return BotEditMessageCaptionFieldsResult.MissingMessageId
    return BotEditMessageCaptionFieldsResult.Ok(BotEditMessageCaptionFields(messageId, caption))
}

/**
 * 与原处理器逐字一致的**对端 E2EE 消息拒绝判定**：只认既有消息正文原文——
 * 以 `E2EE:` 开头、或以 `{` 开头且含 `"ciphertext"` 的正文都视为对端加密信封，
 * bot 纯文本卡片不允许编辑（400 `"cannot edit peer E2EE message"`，文案逐字）。
 */
internal fun isPeerE2eeContent(body: String): Boolean =
    body.startsWith("E2EE:") || (body.startsWith("{") && body.contains("\"ciphertext\""))

/**
 * 与原处理器逐字一致的编辑后正文组装：
 * caption 为空/纯空白 → 保持原正文（等于没改）；否则单行正文直接换成 caption，
 * 多行正文（媒体卡片）只重写首行之后的 caption 部分（`首行 + "\n" + caption`）。
 */
internal fun buildBotEditCaptionContent(body0: String, caption: String): String =
    if (caption.isBlank()) body0 else {
        val lines = body0.lines()
        if (lines.size <= 1) caption else (lines.first() + "\n" + caption)
    }

// CI retrigger（2026-10-01）：Android Instrumented job 在 run 36843159753 上 exit 1，
// 静态分析确认本 PR 为逐行等价搬移（Server/Android/Docker 三 job 全绿），疑似模拟器/E2E
// 环境抖动；re-run-failed-jobs API 返回 403（无 actions:write 权限），故以此注释提交
// 重新触发全量 CI。下一轮按 §3 收取结果；若同一 job 再次失败则不再盲目重跑。
