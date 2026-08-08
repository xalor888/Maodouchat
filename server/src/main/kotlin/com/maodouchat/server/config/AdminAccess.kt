package com.maodouchat.server.config

import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时主管理员集合。
 *
 * 初始化自 [ServerConfig.adminUserIds]（静态 MASTER_ADMINS 环境变量）；
 * 启用 BOOTSTRAP_FIRST_USER_AS_ADMIN 时，注册路由会把数据库中的第一个用户动态加入
 * （一次性引导，解决「先注册拿 ID 再配 MASTER_ADMINS 再重启」的鸡生蛋问题）。
 *
 * 所有管理后台判权必须走 [isAdmin]，不要直接读 [ServerConfig.adminUserIds]。
 */
object AdminAccess {
    private val dynamicAdmins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isAdmin(userId: String): Boolean =
        userId in ServerConfig.adminUserIds || userId in dynamicAdmins

    /** 一次性引导：仅当开关开启且用户表为空时，由注册事务调用。 */
    fun grantAdmin(userId: String) {
        dynamicAdmins.add(userId)
    }

    fun revokeAdmin(userId: String) {
        dynamicAdmins.remove(userId)
    }

    /** 管理员总数（静态 + 动态），仅供审计/日志展示。 */
    fun totalAdmins(): Int = ServerConfig.adminUserIds.size + dynamicAdmins.size
}
