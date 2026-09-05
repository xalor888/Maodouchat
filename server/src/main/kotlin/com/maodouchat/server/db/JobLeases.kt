package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

/**
 * 后台周期任务租约表（B01）。
 *
 * 按项目惯例（新表免版本迁移）：列入 [createSchemaTables] 后新老库自动建表。
 * 行格式：name 主键 + 持有者 + 过期时间；抢占/续约靠行锁 + 条件写，PG/H2 通用。
 */
object JobLeases : Table("job_leases") {
    val name = varchar("name", 100)
    val owner = varchar("owner", 100)
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(name)
}
