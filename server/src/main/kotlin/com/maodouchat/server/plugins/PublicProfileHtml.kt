package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.SealedSenderCertificateService
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayService
import com.maodouchat.server.service.ContentModerationService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.BlobStore
import com.maodouchat.server.service.TurnCredentialService
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.WebRtcBinaryService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import java.util.Base64
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject


// ─── 外部详情页 HTML 渲染 — 类似 t.me 的 /u/{username} ───────────

/** 8.51 修复 H1：完整 HTML 属性/文本转义（& < > " '），公开 HTML 模板必须全量使用。 */
private fun escapeHtml(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (c in value) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private val profilePageTokens = Regex("\\{\\{([A-Z0-9_]+)\\}\\}")

private val profilePageTemplate: String by lazy {
    Thread.currentThread().contextClassLoader?.getResource("public/profile.html")?.readText()
        ?: object {}.javaClass.classLoader.getResource("public/profile.html")?.readText()
        ?: ""
}

private fun renderProfileTemplate(values: Map<String, String>): String {
    val template = profilePageTemplate
    if (template.isBlank()) {
        val title = values["TITLE"].orEmpty()
        val description = values["DESCRIPTION"].orEmpty()
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><title>$title</title></head><body><p>$description</p></body></html>"
    }
    return profilePageTokens.replace(template) { match ->
        values[match.groupValues[1]].orEmpty()
    }
}

private fun profileErrorDescription(error: String): String = when (error) {
    "用户不存在" -> "该用户不存在，或这张分享名片已经失效。"
    "用户名无效" -> "这个分享链接的用户名格式不正确。"
    "请求过于频繁，请稍后再试" -> "访问过于频繁，请稍后再打开这张名片。"
    else -> "暂时无法打开这张公开名片。"
}

private fun resolvePublicBaseUrl(baseUrl: String?): String {
    val raw = baseUrl?.trim().orEmpty()
    return when {
        raw == "/" || raw.startsWith("http://") || raw.startsWith("https://") -> raw.trimEnd('/')
        else -> ""
    }.ifBlank { "/" }.let { if (it == "/") "/" else it }
}

/**
 * 8.52 修复 AI-1：多模态 AI 输入 token 保守估算（防绕过日预算）。
 * 视觉/音频/文件按解码字节 256:1 折算（偏保守，接近真实成本量级），
 * 附加文本按 4 字符/token。此前 transcribe/analyze-image/analyze-file 用
 * estimateTokens("")≈1 token 预留预算，形同虚设。
 */
private fun estimateMultimodalTokens(byteCount: Long, extraText: String? = null): Long {
    val base = if (byteCount > 0) maxOf(1L, byteCount / 256) else 0L
    val textTokens = (extraText?.length ?: 0) / 4L
    return maxOf(1L, base + textTokens)
}
internal fun buildProfilePage(user: UserResponse?, baseUrl: String?, error: String?): String {
    // 8.51 修复 H1：公开主页存储型 XSS——所有用户字段完整 HTML 转义（含 " '）后再填模板
    val rawBase = resolvePublicBaseUrl(baseUrl)
    val escapedBase = escapeHtml(rawBase)
    val year = escapeHtml(java.time.Year.now().value.toString())
    val bodyClass = when {
        error != null -> "state-error"
        user != null -> "state-profile"
        else -> "state-empty"
    }
    val escapedError = error?.let(::escapeHtml).orEmpty()
    val errorDesc = error?.let { escapeHtml(profileErrorDescription(it)) }.orEmpty()
    val safeName = user?.name?.let(::escapeHtml).orEmpty()
    val safeStatus = user?.status?.let(::escapeHtml).orEmpty()
    val safeUsername = user?.username?.let(::escapeHtml).orEmpty()
    val rawAvatar = user?.avatar.orEmpty().trim()
    val isHttpAvatar = rawAvatar.startsWith("http://") || rawAvatar.startsWith("https://")
    val isSameOriginPath = rawAvatar.startsWith("/") && !rawAvatar.startsWith("//")
    val resolvedAvatar = when {
        isHttpAvatar -> rawAvatar
        isSameOriginPath && rawBase != "/" -> "$rawBase$rawAvatar"
        isSameOriginPath -> rawAvatar
        else -> ""
    }
    val safeAvatarUrl = if (resolvedAvatar.isNotBlank()) escapeHtml(resolvedAvatar) else ""
    val avatarImg = if (safeAvatarUrl.isNotBlank()) {
        """<img src="$safeAvatarUrl" alt="$safeName">"""
    } else ""
    val title = when {
        user != null -> "$safeName (@$safeUsername) — 毛豆聊天"
        error != null -> "$escapedError — 毛豆聊天"
        else -> "毛豆聊天"
    }
    val description = when {
        user != null -> {
            val statusText = safeStatus.takeIf { it.isNotBlank() } ?: "毛豆聊天用户"
            "$statusText · @$safeUsername 在毛豆聊天上的个人主页"
        }
        error != null -> errorDesc
        else -> "毛豆聊天 — 安全 · 轻量 · 智能的即时通讯"
    }
    val profileUrl = if (user != null && safeUsername.isNotBlank()) {
        if (rawBase == "/") "/u/$safeUsername" else "$escapedBase/u/$safeUsername"
    } else {
        escapedBase
    }
    val ogImageTags = if (safeAvatarUrl.isNotBlank()) {
        """<meta property="og:image" content="$safeAvatarUrl">"""
    } else ""
    val twitterCard = if (safeAvatarUrl.isNotBlank()) "summary_large_image" else "summary"
    val twitterImageTag = if (safeAvatarUrl.isNotBlank()) {
        """<meta name="twitter:image" content="$safeAvatarUrl">"""
    } else ""
    val classes = buildList {
        add(bodyClass)
        if (safeAvatarUrl.isBlank()) add("no-avatar")
        if (safeStatus.isNotBlank()) add("has-status")
    }.joinToString(" ")
    val initial = escapeHtml(user?.name?.firstOrNull()?.toString() ?: "?")
    val deepLink = if (safeUsername.isNotBlank()) "maodouchat://u/$safeUsername" else "maodouchat://"
    val intentLink = if (safeUsername.isNotBlank()) {
        "intent://u/$safeUsername#Intent;scheme=maodouchat;package=com.maodouchat;end"
    } else escapedBase

    return renderProfileTemplate(
        mapOf(
            "TITLE" to title,
            "DESCRIPTION" to description,
            "CANONICAL" to profileUrl,
            "OG_URL" to profileUrl,
            "OG_IMAGE_TAGS" to ogImageTags,
            "TWITTER_CARD" to twitterCard,
            "TWITTER_IMAGE_TAG" to twitterImageTag,
            "BODY_CLASS" to classes,
            "BASE_HREF" to escapedBase,
            "INITIAL" to initial,
            "AVATAR_URL" to safeAvatarUrl,
            "AVATAR_IMG" to avatarImg,
            "NAME" to safeName,
            "USERNAME" to safeUsername,
            "STATUS" to safeStatus,
            "DEEP_LINK" to deepLink,
            "INTENT_LINK" to intentLink,
            "ERROR_TITLE" to escapedError,
            "ERROR_DESC" to errorDesc,
            "YEAR" to year,
        )
    )
}

/** 9.131：bot 卡片/Hint 端点补齐实时 WS fanout（与 sendMessage/sendTable 等经典端点一致）。 */

/** 9.135：提示文案清洗——hint 进入 SYSTEM 消息并经 WS/FCM 分发到全群，
 * 控制字符/换行会污染客户端渲染与日志；压缩空白并截断到 120 字符。
 * 9.136：改为 internal 供 SecretSurfaceRouting 的 8 个 hint 端点复用。 */