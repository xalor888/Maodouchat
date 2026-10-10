package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.HealthStatusResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Paths

/** 存活/就绪探针与最小状态：无需 JWT，供部署探针与兼容检查使用。 */
internal fun Route.configureHealthProbeRoutes() {
    // 注意：不要在这里注册 get("/")——Routing.kt 在 "/" 提供官网首页；存活探针请用 /health/live。
    get("/health/live") {
        call.respond(HealthStatusResponse(status = "ok"))
    }

    get("/health/ready") {
        call.respondReadiness()
    }

    // 存量部署检查的兼容别名，同样无需 JWT。
    get("/api/health") {
        call.respondReadiness()
    }

    // 最小状态：不含 APP_ENV，避免向匿名者泄露 development/production 环境信息。
    get("/api/status") {
        call.respondText(
            """{"status":"ok","service":"Maodouchat Server","version":"1.0.0"}""",
            contentType = io.ktor.http.ContentType.Application.Json
        )
    }
}

internal suspend fun ApplicationCall.respondReadiness() {
    val databaseReady = com.maodouchat.server.db.isDatabaseReady()
    // B14：迁移状态——已应用版本需达到期望最新版本，否则判定未就绪（滚动发布期间暂停流量）。
    // 表缺失（直接建表/测试环境）视为无待办迁移，仅当表存在但版本落后才判 pending。
    val migrationsReady = runCatching {
        val applied = com.maodouchat.server.db.migration.appliedMigrationVersion()
        applied == null || applied >= com.maodouchat.server.db.migration.expectedMigrationVersion()
    }.getOrDefault(false)
    val storageReady = runCatching {
        val path = Paths.get(ServerConfig.storageDir).toAbsolutePath().normalize()
        Files.isDirectory(path) && Files.isWritable(path)
    }.getOrDefault(false)
    // B14：后台周期任务状态——连续失败 >= 阈值判 degraded；从未运行为 unknown，不拉低就绪。
    val backgroundTasks = com.maodouchat.server.service.BackgroundTaskHealth.global
    val backgroundDegraded = backgroundTasks.degradedTasks()
    val checks = linkedMapOf(
        "database" to if (databaseReady) "ok" else "unavailable",
        "migrations" to if (migrationsReady) "ok" else "pending",
        "storage" to if (storageReady) "ok" else "unavailable",
        "backgroundTasks" to if (backgroundDegraded.isEmpty()) "ok" else "degraded:${backgroundDegraded.joinToString(",")}",
    )
    if (databaseReady && migrationsReady && storageReady && backgroundDegraded.isEmpty()) {
        respond(HealthStatusResponse(status = "ready", checks = checks))
    } else {
        respond(HttpStatusCode.ServiceUnavailable, HealthStatusResponse(status = "not_ready", checks = checks))
    }
}
