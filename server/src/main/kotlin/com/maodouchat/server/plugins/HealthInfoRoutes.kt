package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Paths
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 构建/版本与服务器身份信息：无需 JWT，供部署看板与客户端展示。 */
internal fun Route.configureHealthInfoRoutes() {
    // 版本号取 APP_VERSION 环境变量，缺省为 dev。
    get("/health/info") {
        val version = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "dev"
        val info = buildJsonObject {
            put("service", "Maodouchat Server")
            put("version", version)
            put("env", ServerConfig.appEnv)
            put("timestamp", System.currentTimeMillis())
        }
        call.respondText(info.toString(), contentType = ContentType.Application.Json)
    }

    // 第三方服务器模式的身份/品牌信息；公告支持免重启更新。
    get("/api/server/info") {
        val version = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "dev"
        val name = System.getenv("SERVER_NAME")?.takeIf { it.isNotBlank() } ?: "Maodouchat Server"
        val description = System.getenv("SERVER_DESCRIPTION").orEmpty().take(500)
        val contactUrl = System.getenv("SERVER_CONTACT_URL").orEmpty().take(300)
        val announcement = run {
            // 9.210：优先级——管理后台运行时公告 > 存储目录文件 > 环境变量，
            // 运营方无需重启即可更新公告
            val runtime = runCatching {
                com.maodouchat.server.service.RuntimeConfigService
                    .get(com.maodouchat.server.service.RuntimeConfigService.KEY_PUBLIC_ANNOUNCEMENT)
            }.getOrDefault("").trim()
            if (runtime.isNotBlank()) {
                runtime.take(1000)
            } else {
                val file = Paths.get(ServerConfig.storageDir).resolve("server-announcement.txt").toFile()
                val fromFile = if (file.isFile) runCatching { file.readText().trim() }.getOrNull() else null
                (fromFile?.takeIf { it.isNotBlank() } ?: System.getenv("SERVER_ANNOUNCEMENT").orEmpty()).take(1000)
            }
        }
        val body = buildJsonObject {
            put("name", name)
            put("description", description)
            put("announcement", announcement)
            put("contactUrl", contactUrl)
            put("version", version)
            put("registrationOpen", ServerConfig.allowRegistration)
            put("timestamp", System.currentTimeMillis())
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=60")
        call.respondText(body.toString(), contentType = ContentType.Application.Json)
    }
}
