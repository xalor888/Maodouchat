package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.HttpHeaders

/** 内部更新发布：部署凭证校验 + APK 落盘 + manifest/运行时配置。 */
internal fun Route.configureInternalAppUpdateRoutes() {
    put("/api/internal/app-update") {
        val expected = ServerConfig.updateDeployToken
        if (!com.maodouchat.server.update.AppUpdatePublishPolicy.tokenConfigured(expected)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("未配置更新发布"))
            return@put
        }
        val provided = com.maodouchat.server.update.AppUpdatePublishPolicy.bearerToken(
            call.request.header(HttpHeaders.Authorization)
        ).orEmpty()
        if (!com.maodouchat.server.update.AppUpdatePublishPolicy.tokensMatch(expected, provided)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("发布凭证无效"))
            return@put
        }
        val versionCode = com.maodouchat.server.update.AppUpdatePublishPolicy.parseVersionCode(call.request.header("X-Version-Code"))
        val versionName = com.maodouchat.server.update.AppUpdatePublishPolicy.parseVersionName(call.request.header("X-Version-Name"))
        if (versionCode == null || versionName == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("版本号无效"))
            return@put
        }
        // B14：不可降级——拒绝等于或低于当前已发布版本的发布。
        val currentVersionCode = RuntimeConfigService.getInt(RuntimeConfigService.KEY_UPDATE_VERSION_CODE, 0)
        if (com.maodouchat.server.update.AppUpdatePublishPolicy.isDowngrade(versionCode, currentVersionCode)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("版本号不能低于或等于当前已发布版本"))
            return@put
        }
        val notes = com.maodouchat.server.update.AppUpdatePublishPolicy.sanitizeNotes(call.request.header("X-Update-Notes"))
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                com.maodouchat.server.update.AppUpdateStorage.saveFromStream(call.receiveStream())
            }
        }.getOrElse { error ->
            call.application.environment.log.warn("app-update write failed: ${error.message}", error)
            val message = when (error.message) {
                "too_large" -> "APK 过大"
                "too_small", "not_apk" -> "不是有效的 APK"
                else -> "写入更新包失败"
            }
            val status = if (error.message == "too_large") HttpStatusCode(413, "Payload Too Large") else HttpStatusCode.BadRequest
            call.respond(status, ErrorResponse(message))
            return@put
        }
        val apkUrl = com.maodouchat.server.update.AppUpdatePublishPolicy.publicApkUrl(ServerConfig.baseUrl)
        val apkSha256 = com.maodouchat.server.update.AppUpdateStorage.latestSha256()
            ?: error("update APK checksum unavailable")
        // B14：先写不可变制品 manifest（sha256 与 APK 字节绑定），再写可编辑的运行时配置。
        com.maodouchat.server.update.AppUpdateStorage.writeManifest(versionCode, versionName, apkSha256, saved.length())
        RuntimeConfigService.applyPublishedUpdate(versionCode, versionName, apkUrl, apkSha256, notes)
        call.respond(buildJsonObject {
            put("ok", true)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("apkSha256", apkSha256)
            put("bytes", saved.length())
        })
    }
}
