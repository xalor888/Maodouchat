package com.maodouchat.server.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private fun loadPublicHtml(page: String): String? =
    Thread.currentThread().contextClassLoader?.getResource("public/$page.html")?.readText()
        ?: object {}.javaClass.classLoader.getResource("public/$page.html")?.readText()

private suspend fun ApplicationCall.respondPublicHtml(page: String, fallback: String = "<h1>Maodouchat</h1>") {
    response.header(HttpHeaders.CacheControl, "no-cache, must-revalidate")
    respondText(loadPublicHtml(page) ?: fallback, ContentType.Text.Html)
}

/** 官网页面：首页 / 开发者 / 隐私 / 条款 / 安全，及旧页面重定向。 */
internal fun Route.configurePublicSitePageRoutes() {
    get("/") {
        // 第三方部署可关闭官网（PUBLIC_SITE=false），首页改为极简服务器名片
        if (!com.maodouchat.server.config.ServerConfig.publicSiteEnabled) {
            fun esc(value: String): String = value
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
            val name = esc(System.getenv("SERVER_NAME")?.takeIf { it.isNotBlank() } ?: "Maodouchat Server")
            val desc = esc(System.getenv("SERVER_DESCRIPTION").orEmpty())
            call.respondText(
                """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><meta name="robots" content="noindex"/><title>$name</title><style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;background:#f4f5f7;min-height:100vh;display:flex;align-items:center;justify-content:center;color:#23272b}.card{background:#fff;border-radius:16px;box-shadow:0 1px 3px rgba(16,24,40,.06);padding:40px 32px;max-width:420px;width:calc(100% - 32px);text-align:center}.mark{width:56px;height:56px;border-radius:14px;background:#3390EC;color:#fff;font-size:24px;font-weight:600;display:flex;align-items:center;justify-content:center;margin:0 auto 16px}h1{font-size:20px;font-weight:600;color:#111418;margin-bottom:6px}.desc{font-size:14px;color:#6b7280;line-height:1.6;margin-bottom:14px}.foot{font-size:12px;color:#a2a8b0}@media (prefers-color-scheme:dark){body{background:#101418}.card{background:#1a1f24;box-shadow:none}h1{color:#f2f4f6}.desc{color:#9aa1a9}}</style></head><body><div class="card"><div class="mark">毛</div><h1>$name</h1>${if (desc.isNotBlank()) "<p class=\"desc\">$desc</p>" else ""}<p class="foot">Powered by Maodouchat Server</p></div></body></html>""",
                ContentType.Text.Html
            )
            return@get
        }
        call.respondPublicHtml("index")
    }

    get("/developer") {
        call.respondPublicHtml("developer", "<h1>Developer Console</h1>")
    }

    get("/developer.html") {
        call.respondRedirect("/developer", permanent = true)
    }

    get("/privacy") {
        call.respondPublicHtml("privacy", "<h1>Privacy Policy</h1>")
    }

    get("/privacy.html") {
        call.respondRedirect("/privacy", permanent = true)
    }

    get("/terms") {
        call.respondPublicHtml("terms", "<h1>Terms of Service</h1>")
    }

    get("/terms.html") {
        call.respondRedirect("/terms", permanent = true)
    }

    get("/security") {
        call.respondPublicHtml("security", "<h1>Security</h1>")
    }

    get("/security.html") {
        call.respondRedirect("/security", permanent = true)
    }

    // 旧页面永久重定向到首页相应模块。
    get("/faq") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/faq.html") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/help") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/help.html") {
        call.respondRedirect("/#faq", permanent = true)
    }
}
