package com.maodouchat.server.service

/**
 * B13：运营统计只读 query model——管理后台 /dashboard、/system-stats、/trends、/online、
 * /ranking、/storage、/rich-trends 与审计导出背后的数据库查询，统一从 AdminObservabilityRouting
 * 抽出，路由只负责鉴权与 HTTP 序列化，不再内嵌事务。
 */
object OperationsQueryService {

    internal const val DAY_MS = 86_400_000L

}
