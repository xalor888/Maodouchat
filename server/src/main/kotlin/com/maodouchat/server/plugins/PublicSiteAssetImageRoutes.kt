package com.maodouchat.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 官网静态资源：图标图片。 */
internal fun Route.configurePublicSiteAssetImageRoutes() {
    get("/assets/logo.png") {
        val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/logo.png")?.use { it.readBytes() }
        if (bytes != null) {
            call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/assets/icon-192.png") {
        val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/icon-192.png")?.use { it.readBytes() }
        if (bytes != null) {
            call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/assets/icon-512.png") {
        val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/icon-512.png")?.use { it.readBytes() }
        if (bytes != null) {
            call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
