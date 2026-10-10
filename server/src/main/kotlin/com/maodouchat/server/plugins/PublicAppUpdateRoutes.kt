package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 公开应用更新：版本元数据 / 最新 APK 下载。 */
internal fun Route.configurePublicAppUpdateRoutes() {
    get("/api/public/updates") {
        // B14：制品完整性字段（versionCode/versionName/sha256）优先取不可变 manifest，
        // 无 manifest（旧部署）回退到可编辑的运行时配置。
        val manifest = com.maodouchat.server.update.AppUpdateStorage.readManifest()
        val versionCode = manifest?.get("versionCode")?.toIntOrNull()
            ?: RuntimeConfigService.getInt(RuntimeConfigService.KEY_UPDATE_VERSION_CODE, 0)
        val versionName = manifest?.get("versionName")?.takeIf { it.isNotBlank() }
            ?: RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_VERSION_NAME).ifBlank { "0" }
        val apkSha256 = manifest?.get("apkSha256")?.takeIf { it.isNotBlank() }
            ?: RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_APK_SHA256)
        val apkUrl = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_APK_URL)
        val serverUrl = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_SERVER_URL).ifBlank { ServerConfig.baseUrl }
        val notes = RuntimeConfigService.get(RuntimeConfigService.KEY_UPDATE_NOTES)
        call.respond(buildJsonObject {
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("apkSha256", apkSha256)
            put("serverUrl", serverUrl)
            put("notes", notes)
        })
    }

    get("/api/public/app-update/latest.apk") {
        val file = com.maodouchat.server.update.AppUpdateStorage.latestFile()
        if (!file.isFile || file.length() < com.maodouchat.server.update.AppUpdatePublishPolicy.MIN_APK_BYTES) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("更新包尚未发布"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat.apk\"")
        call.respondFile(file)
    }
}
