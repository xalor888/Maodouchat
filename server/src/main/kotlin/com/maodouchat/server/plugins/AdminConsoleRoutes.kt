package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理控制台：Dashboard 页与水印自检（从 AdminOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminConsoleRoutes(
) {

get("/watermark/self-test") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val adminId = call.requireUserId()
    val sample = com.maodouchat.server.watermark.AdminWatermarkExtractor.embedDemoPngBase64(
        userId = adminId,
        chatId = "self-test-chat",
        deviceHint = "admin-console"
    )
    val extracted = com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(sample)
    call.respond(
        WatermarkSelfTestResponse(
            samplePngBase64 = sample,
            found = extracted.found,
            payloadHex = extracted.payloadHex.orEmpty(),
            message = extracted.message
        )
    )
}

get("/dashboard.html") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    call.respondAdminDashboardPage()
}
}
