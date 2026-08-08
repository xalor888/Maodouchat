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
