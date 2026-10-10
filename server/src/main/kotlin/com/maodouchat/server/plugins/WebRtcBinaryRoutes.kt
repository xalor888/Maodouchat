package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import com.maodouchat.server.service.WebRtcBinaryService

/** WebRTC 原生库交付：按 ABI 分发，ETag 缓存。 */
internal fun Route.configureWebRtcBinaryRoutes() {
    get("/api/webrtc/lib/{abi}") {
        val abi = parseRawOrEmpty(call.parameters, "abi")
        if (!WebRtcBinaryService.isSupported(abi)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("不支持的 CPU 架构: $abi"))
            return@get
        }
        val file = WebRtcBinaryService.resolveFile(abi)
        if (file == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("WebRTC 原生库暂不可用"))
            return@get
        }
        val etag = WebRtcBinaryService.sha256(file, abi)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000, immutable")
        call.response.header(HttpHeaders.ETag, "\"$etag\"")
        call.response.header("X-Content-SHA256", etag)
        call.response.header(HttpHeaders.ContentEncoding, "identity")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == "\"$etag\"") {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondFile(file)
    }
}
