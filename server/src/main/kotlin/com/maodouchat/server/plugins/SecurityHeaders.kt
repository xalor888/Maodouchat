package com.maodouchat.server.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * 全局安全响应头插件。
 *
 * 仅补充不依赖响应内容、且不会影响现有前端渲染的基础防护头:
 * - X-Content-Type-Options: nosniff  防止 MIME 嗅探
 * - X-Frame-Options: DENY           禁止被嵌入 iframe(点击劫持)
 * - Referrer-Policy: no-referrer    不泄漏来源 URL
 *
 * 在响应提交前(onCallRespond)补设,且仅在尚未设置时追加,避免与个别路由
 * (如管理后台已自带 CSP/X-Frame-Options)产生重复头。
 *
 * 未加的项(HSTS / 严格 CSP)见审计报告:需产品/架构确认全站 HTTPS 与前端
 * 富文本/WebView 兼容性后再决定,此处不擅自开启。
 */
val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCallRespond { call ->
        val headers = call.response.headers
        if (headers["X-Content-Type-Options"] == null) {
            headers.append("X-Content-Type-Options", "nosniff")
        }
        if (headers["X-Frame-Options"] == null) {
            headers.append("X-Frame-Options", "DENY")
        }
        if (headers["Referrer-Policy"] == null) {
            headers.append("Referrer-Policy", "no-referrer")
        }
    }
}
