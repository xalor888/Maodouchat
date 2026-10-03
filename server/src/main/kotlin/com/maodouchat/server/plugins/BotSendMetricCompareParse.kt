package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotMetricCompareFields(
    val chatId: String,
    val first: String,
    val second: String,
    val third: String,
)

internal sealed interface BotMetricCompareFieldsResult {
    data class Ok(val fields: BotMetricCompareFields) : BotMetricCompareFieldsResult
    data object MissingRequired : BotMetricCompareFieldsResult
}

internal fun parseBotMetricCompareFields(
    obj: JsonObject,
    keyA: String, defaultA: String, capA: Int,
    keyB: String, defaultB: String, capB: Int,
    keyC: String?, defaultC: String, capC: Int,
): BotMetricCompareFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接存在性——键缺席才回默认；显式 JSON null 得字面 "null"（非 null，不回退）；
    // 键在但空白原样保留（不回默认）；不 trim，前导空格计入 take 上限（原处理器逐字顺序）。
    val first = (obj[keyA]?.jsonPrimitive?.content ?: defaultA).take(capA)
    val second = (obj[keyB]?.jsonPrimitive?.content ?: defaultB).take(capB)
    val third = if (keyC == null) "" else (obj[keyC]?.jsonPrimitive?.content ?: defaultC).take(capC)
    // 注意：单必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank()) {
        return BotMetricCompareFieldsResult.MissingRequired
    }
    return BotMetricCompareFieldsResult.Ok(BotMetricCompareFields(chatId, first, second, third))
}

/**
 * 与原处理器逐字一致的内容组装：`"**" + label + "**  \n`" + value + unit 后缀 + "`"`
 *（unit 非空白时后缀为 `" " + unit`，否则为空；markdown 硬换行的两个空格逐字保留）。
 */
internal fun buildBotMetricContent(label: String, value: String, unit: String): String {
    val unitSuffix = if (unit.isNotBlank()) " " + unit else ""
    return "**" + label + "**  \n`" + value + unitSuffix + "`"
}

/**
 * 与原处理器逐字一致的内容组装：单行对比表格
 * `"| Left | Right |\n| --- | --- |\n| " + left + " | " + right + " |"`。
 */
internal fun buildBotCompareContent(left: String, right: String): String =
    "| Left | Right |\n| --- | --- |\n| " + left + " | " + right + " |"
