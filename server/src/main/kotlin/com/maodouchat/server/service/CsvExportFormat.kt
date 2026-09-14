package com.maodouchat.server.service

/**
 * CSV 单元格编码（含 Excel 公式注入防护）。
 *
 * M2：从 `plugins/AdminSupport.kt` 迁到 `service/`。原因是 CSV 组装归 service 层所有，
 * 而 `Service` 不得反向依赖 `plugins`（`ServerArchitectureTest` 的棘轮守着这条方向）。
 * 迁移后依赖方向是 `plugins → service`，与既有契约一致。
 */
internal fun csvCell(value: Any?): String {
    val raw = value?.toString() ?: ""
    // 公式注入防护须按「去除前导空白后的首字符」判定：Excel 会忽略前导空白/制表符
    // 求值单元格，此前仅查原始首字符，`" =CMD()"` 这类以空格开头的载荷仍会执行。
    val formulaSafe = if (raw.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"${formulaSafe.replace("\"", "\"\"")}\""
}
