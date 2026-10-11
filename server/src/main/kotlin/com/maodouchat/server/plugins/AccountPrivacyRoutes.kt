package com.maodouchat.server.plugins

import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 隐私与通知：隐私设置、通知偏好（按域拆为 2 簇的委托门面）。 */
internal fun Route.configureAccountPrivacyRoutes(
    userRepo: UserRepository,
    notificationPreferenceRepo: NotificationPreferenceRepository,
    json: Json,
) {
    configureAccountPrivacyPolicyRoutes(userRepo, json)
    configureAccountNotificationSettingsRoutes(notificationPreferenceRepo)
}
