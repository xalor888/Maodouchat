package com.maodouchat.server.service

/** 导出结果：CSV 正文 + 数据行数（不含表头，供审计 detail 使用）。 */
data class CsvExport(val body: String, val rowCount: Int)
