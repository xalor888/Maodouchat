package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Paths

// 贴纸包/文件名白名单：提到文件级复用，避免每请求重复编译。
private val stickerPackIdSanitizeRegex = Regex("[^A-Za-z0-9_-]")
private val stickerNameSanitizeRegex = Regex("[^A-Za-z0-9._-]")

private const val STICKER_MANIFEST_EMPTY =
    """{"version":1,"packs":[]}"""

/** 按需贴纸包清单与贴纸文件交付。 */
internal fun Route.configureStickerRoutes() {
    // 贴纸包清单：文件存在则原样返回，不存在返回空清单（客户端回退内置表情）。
    get("/api/stickers/manifest.json") {
        val manifestFile = Paths.get(ServerConfig.storageDir).resolve("stickers-manifest.json").toFile()
        val body = if (manifestFile.isFile) {
            runCatching { manifestFile.readText().trim() }
                .getOrElse { STICKER_MANIFEST_EMPTY }
                .takeIf { it.isNotBlank() }
                ?: STICKER_MANIFEST_EMPTY
        } else {
            STICKER_MANIFEST_EMPTY
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(body, contentType = ContentType.Application.Json)
    }

    // packId/file 做字符白名单 + canonical 路径前缀校验，防路径穿越。
    get("/static/stickers/{packId}/{name}") {
        val rawPack = parseRawOrEmpty(call.parameters, "packId")
        val rawName = parseRawOrEmpty(call.parameters, "name")
        val packId = rawPack.replace(stickerPackIdSanitizeRegex, "").take(40)
        val name = rawName.replace(stickerNameSanitizeRegex, "").take(80)
        if (packId.isEmpty() || name.isEmpty() || packId != rawPack || name != rawName) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid sticker path"))
            return@get
        }
        val storageRoot = Paths.get(ServerConfig.storageDir).toAbsolutePath().normalize()
        val base = storageRoot.resolve("stickers").normalize()
        val file = base.resolve(packId).resolve(name).normalize().toFile()
        if (!file.isFile || !file.canonicalFile.toPath().startsWith(base)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("sticker not found"))
            return@get
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondFile(file)
    }
}
