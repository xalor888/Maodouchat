package com.maodouchat.server.service

// CSV 编码核心：行编码 + 整表组装（各导出簇共享）。

internal fun AdminExportService.encodeRow(row: List<Any?>): String =
    row.joinToString(",") { cell -> csvCell(cell) }

internal fun AdminExportService.csv(header: String, rows: List<List<Any?>>): CsvExport = CsvExport(
    body = buildString {
        appendLine(header)
        rows.forEach { row -> appendLine(encodeRow(row)) }
    },
    rowCount = rows.size,
)
