package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route

/** 系统公告生命周期（发布/取消/统计）。 */
internal fun Route.configureAnnouncementLifecycleRoutes(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    fcmPushService: FcmPushService?,
    pushTokenRepo: PushTokenRepository?,
) {
    configureAnnouncementLifecyclePublishRoutes(
        announcementRepo = announcementRepo,
        userTagRepo = userTagRepo,
        fcmPushService = fcmPushService,
        pushTokenRepo = pushTokenRepo,
    )
    configureAnnouncementLifecycleStatsRoutes(
        announcementRepo = announcementRepo,
    )
}