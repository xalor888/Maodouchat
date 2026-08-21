package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import org.slf4j.LoggerFactory

private val corsLog = LoggerFactory.getLogger("CORS")

/**
 * Configures CORS for the server.
 * In development, allows all origins.
 * In production, only allows configured origins via CORS_ORIGINS env var.
 */
fun Application.configureCORS() {
    install(CORS) {
        if (ServerConfig.corsOrigins.isEmpty()) {
            if (ServerConfig.isProduction) {
                corsLog.warn("CORS_ORIGINS not configured in production; defaulting to same-origin only.")
            } else {
                // 开发模式 - 允许所有源
                anyHost()
            }
            // 9.5xx：放行「自身服务源」——浏览器同源 fetch 仍携带 Origin 头，
            // 此前空白名单下 Ktor 对一切带 Origin 的请求回 403：管理后台页面在浏览器里
            // 登录永远 403（curl / Android App 不带 Origin 所以表现正常）。
            // 仅允许 BASE_URL 自身的协议+主机，跨域源仍被拒绝，不扩大攻击面。
            runCatching { io.ktor.http.Url(ServerConfig.baseUrl) }
                .onSuccess { url ->
                    allowHost(url.host, schemes = listOf(url.protocol.name))
                }
                .onFailure { e ->
                    corsLog.warn("Failed to parse BASE_URL for same-origin CORS: {}", e.message)
                }
        } else {
            // Production — only allow configured origins
            ServerConfig.corsOrigins.forEach { host ->
                allowHost(host.removePrefix("https://").removePrefix("http://"),
                    schemes = listOf(if (host.startsWith("https://")) "https" else "http"))
            }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Requested-With")
        // 仅在配置了明确白名单主机(生产)时启用凭据;
        // 开发模式的 anyHost() 绝不开启 allowCredentials,避免 anyHost + 凭证导致的跨域凭据泄露。
        // 服务端当前不通过 Cookie 下发任何凭据(鉴权走 Authorization 头),无凭据需求时可保持关闭。
        if (ServerConfig.corsOrigins.isNotEmpty()) {
            allowCredentials = true
        }
        maxAgeInSeconds = 3600
    }
}
