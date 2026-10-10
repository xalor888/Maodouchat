package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserTagRepository
import io.ktor.server.application.Application

/**
 * 用户标签子域路由：标签 CRUD、风险联动、按用户打标/摘标、风险汇总。
 * 从 AdminEnhanceRouting 拆出（B13 子项 3：管理 route 按内容审核拆分）。
 */
fun Application.configureUserTagRoutes(userTagRepo: UserTagRepository) {
    configureUserTagCrudRoutes(userTagRepo)
    configureUserTagAssignmentRoutes(userTagRepo)
}
