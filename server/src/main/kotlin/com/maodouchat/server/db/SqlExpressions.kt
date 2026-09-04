package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.QueryBuilder

/**
 * 把时间列按「Unix 天编号」（timestamp / 86400000）分组的 Exposed 表达式，
 * 供趋势统计 SQL GROUP BY 聚合（管理仪表盘 /trends、/rich-trends 与开发者分析）。
 * 用 column.toQueryBuilder 输出「表名.列名」，CAST 用跨库 BIGINT。
 */
internal fun dayBucketExpression(column: Column<Long>): Expression<Long> =
    object : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("CAST(")
            column.toQueryBuilder(queryBuilder)
            queryBuilder.append(" / 86400000 AS BIGINT)")
        }
    }
