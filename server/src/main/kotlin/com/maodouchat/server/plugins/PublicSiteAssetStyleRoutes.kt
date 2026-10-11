package com.maodouchat.server.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private suspend fun ApplicationCall.respondPublicAsset(resource: String, contentType: ContentType, fallback: String = "") {
    val content = object {}.javaClass.classLoader.getResource(resource)?.readText() ?: fallback
    response.header(HttpHeaders.CacheControl, "public, max-age=3600")
    respondText(content, contentType)
}

/** 官网静态资源：样式与脚本。 */
internal fun Route.configurePublicSiteAssetStyleRoutes() {
    get("/assets/site.css") { call.respondPublicAsset("public/assets/site.css", ContentType.Text.CSS, "body{font-family:sans-serif}") }

    get("/assets/home.css") { call.respondPublicAsset("public/assets/home.css", ContentType.Text.CSS) }

    get("/assets/profile.css") { call.respondPublicAsset("public/assets/profile.css", ContentType.Text.CSS) }

    get("/assets/style.css") { call.respondPublicAsset("public/assets/style.css", ContentType.Text.CSS) }

    get("/assets/developer.css") { call.respondPublicAsset("public/assets/developer.css", ContentType.Text.CSS) }

    get("/assets/developer.js") { call.respondPublicAsset("public/assets/developer.js", ContentType.Application.JavaScript) }
}
