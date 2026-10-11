package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 官网静态资源：sitemap、security.txt、manifest、sw.js、robots.txt。 */
internal fun Route.configurePublicSiteAssetWellKnownRoutes() {
    get("/sitemap.xml") {
        val base = ServerConfig.baseUrl.trimEnd('/')
        val pages = listOf("", "developer", "security", "privacy", "terms")
        val urls = pages.joinToString("") { page ->
            val loc = if (page.isBlank()) "$base/" else "$base/$page"
            "<url><loc>$loc</loc><changefreq>weekly</changefreq></url>"
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(
            """<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">$urls</urlset>""",
            io.ktor.http.ContentType.Text.Xml
        )
    }

    get("/.well-known/security.txt") {
        val base = ServerConfig.baseUrl.trimEnd('/')
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(
            "Contact: mailto:security@maodouchat.com\nPreferred-Languages: zh, en\nCanonical: $base/.well-known/security.txt\nPolicy: $base/security#disclosure\nExpires: 2027-08-13T00:00:00.000Z\n",
            io.ktor.http.ContentType.Text.Plain
        )
    }

    get("/security.txt") {
        call.respondRedirect("/.well-known/security.txt", permanent = true)
    }

    get("/manifest.webmanifest") {
        val manifest = Thread.currentThread().contextClassLoader
            ?.getResource("public/manifest.webmanifest")?.readText()
            ?: object {}.javaClass.classLoader.getResource("public/manifest.webmanifest")?.readText()
            ?: "{}"
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(manifest, io.ktor.http.ContentType.Application.Json)
    }

    get("/sw.js") {
        val sw = Thread.currentThread().contextClassLoader
            ?.getResource("public/sw.js")?.readText()
            ?: object {}.javaClass.classLoader.getResource("public/sw.js")?.readText()
            ?: ""
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(sw, io.ktor.http.ContentType.Application.JavaScript)
    }

    get("/robots.txt") {
        val base = ServerConfig.baseUrl.trimEnd('/')
        call.respondText(
            "User-agent: *\nAllow: /\nDisallow: /admin\nDisallow: /developer\nDisallow: /developer.html\nDisallow: /api/\nSitemap: $base/sitemap.xml\n",
            io.ktor.http.ContentType.Text.Plain
        )
    }
}
