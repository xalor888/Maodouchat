package com.maodouchat.server.plugins

import com.maodouchat.server.repository.PushTokenRepository
import io.ktor.server.routing.Route

/** 推送：verify-key 派生密钥查询、push token 注册/注销（按域拆为 2 簇的委托门面）。 */
internal fun Route.configureAccountPushRoutes(
    pushTokenRepo: PushTokenRepository,
) {
    configureAccountPushKeyRoutes()
    configureAccountPushTokenRoutes(pushTokenRepo)
}
