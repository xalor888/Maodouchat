package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** 系统公告管理端：按域拆为 CRUD / 生命周期两簇。 */
fun Application.configureAnnouncementAdminRoutes(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    fcmPushService: FcmPushService?,
    pushTokenRepo: PushTokenRepository?,
) {
    routing {
        authenticate("admin-jwt") {
            route("/api/admin") {
                configureAnnouncementCrudRoutes(announcementRepo)
                configureAnnouncementLifecycleRoutes(announcementRepo, userTagRepo, fcmPushService, pushTokenRepo)
            }
        }
    }
}
