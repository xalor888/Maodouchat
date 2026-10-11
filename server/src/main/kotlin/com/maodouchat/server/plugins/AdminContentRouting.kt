package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.PostRepository
import io.ktor.server.routing.Route

/**
 * 管理后台「内容管理（动态 / 评论）」子域路由。鉴权 + DTO/查询 + repository 调用 + 错误映射；
 * 删除动态后广播 WS 事件是提交后副作用，不反向回滚已提交的删除。
 */
internal fun Route.configureAdminContentRoutes(
    postRepo: PostRepository,
    adminManagementRepo: AdminManagementRepository = AdminManagementRepository()
) {
    configureAdminContentQueryRoutes(adminManagementRepo)
    configureAdminContentWriteRoutes(postRepo)
}
