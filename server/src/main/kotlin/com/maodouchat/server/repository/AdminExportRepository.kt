package com.maodouchat.server.repository

/**
 * M2：管理后台 CSV 导出的**唯一 SQL/Exposed 边界**。
 *
 * 此前 `AdminExportsRouting.kt` 在 26 个 handler 里各自 `transaction { … }` 直写 Exposed DSL，
 * 既破坏「repository=SQL 边界」契约，也让 27 个导出的查询逻辑无法被单测覆盖。
 * 这里只负责「取数」：每个方法返回原始单元格（`Any?`），
 * **不做 CSV 编码**——转义与公式注入防护属于 [com.maodouchat.server.service.AdminExportService]。
 *
 * 迁移原则：逐字搬运，行为不变；本类不引入任何新的过滤/排序/截断语义。
 */
class AdminExportRepository(
    internal val authTokenRepo: AuthTokenRepository = AuthTokenRepository(),
)
