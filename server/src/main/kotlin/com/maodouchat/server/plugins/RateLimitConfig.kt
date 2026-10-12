package com.maodouchat.server.plugins

// 附件分块上传路径：拦截器里每个请求都在重新编译，提到文件级复用。
internal val attachmentChunkPathRegex = Regex("^/api/attachments/[^/]+/chunks?$")

// Env helpers mirror ServerConfig.env(): real env first, system property second, default last.
internal fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(name)?.takeIf(String::isNotBlank)
        ?: default

internal fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull()?.coerceAtLeast(1) ?: default

internal fun envLong(name: String, default: Long): Long =
    env(name, default.toString()).toLongOrNull()?.coerceAtLeast(1L) ?: default
