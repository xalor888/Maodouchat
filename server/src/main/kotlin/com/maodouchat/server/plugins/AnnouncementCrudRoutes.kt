package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AnnouncementRepository
import io.ktor.server.routing.Route

/** 系统公告 CRUD（列表/创建/详情/更新/删除）。 */
internal fun Route.configureAnnouncementCrudRoutes(
    announcementRepo: AnnouncementRepository,
) {
    configureAnnouncementCrudReadRoutes(
        announcementRepo = announcementRepo,
    )
    configureAnnouncementCrudWriteRoutes(
        announcementRepo = announcementRepo,
    )
}