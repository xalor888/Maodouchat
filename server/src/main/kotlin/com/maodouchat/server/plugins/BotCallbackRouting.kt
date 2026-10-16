package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 回调应答与更新轮询（answerCallbackQuery / getUpdates）。 */
internal fun Route.configureBotCallbackRoutes(botSendRateLimiter: BoundedRateLimiter) {
    configureBotCallbackWriteRoutes(botSendRateLimiter)
    configureBotCallbackQueryRoutes(botSendRateLimiter)
}
