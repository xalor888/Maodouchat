package com.maodouchat.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 本机拉取首屏 HTML 并解析 OG。进程内缓存；失败静默。
 * 显式接管重定向链路，在重定向发生前执行 SSRF 安全审查与降级防护，阻断危险重定向。
 */
object LinkPreviewRepository {
    private const val TAG = "LinkPreview"
    private const val MAX_BYTES = 128_000
    private const val CACHE_LIMIT = 120
    private const val MAX_REDIRECTS = 3

    internal var clientOverride: OkHttpClient? = null

    // 关键防护：OkHttp 禁用自动重定向跟随，由应用层显式校验每一跳的重定向目标 URL
    // （该语义已固化在 HttpClients.linkPreview() 里）；这里只额外挂上自定义 DNS。
    private val defaultClient: OkHttpClient = com.maodouchat.network.HttpClients.linkPreview()
        .newBuilder()
        .dns(PublicNetworkDns.create())
        .build()

    private val client: OkHttpClient get() = clientOverride ?: defaultClient

    private val cache = ConcurrentHashMap<String, LinkPreviewPolicy.Preview>()
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()
    private class InFlightLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val inFlight = ConcurrentHashMap<String, InFlightLock>()
    private val generation = AtomicLong(0L)
    // 9.142：跨 URL 并发 fetch 各自持 per-URL 锁，共享缓存的裁剪需要独立全局监视器——
    // 此前两个并发 fetch 同时算 remaining 并批量删除，会超额清空缓存
    private val trimMonitor = Any()

    fun cached(url: String): LinkPreviewPolicy.Preview? = cache[url]

    fun clear() {
        generation.incrementAndGet()
        cache.clear()
        negativeCache.clear()
        inFlight.clear()
    }

    /**
     * @return 有用预览或 null（失败/无 meta 均 null，调用方不提示）
     */
    suspend fun fetch(url: String): LinkPreviewPolicy.Preview? {
        // 0.80：链接预览运行时开关——服务端可整体关闭（此前 flag 写入但从未生效）
        if (!RuntimeFlags.isEnabled(com.maodouchat.MaodouchatApp.instance, RuntimeFlags.LINK_PREVIEW)) return null
        val fetchGeneration = generation.get()
        val safe = LinkPreviewPolicy.sanitizeUrl(url) ?: return null
        cache[safe]?.let { return it }
        if (safe in negativeCache) return null
        val lock = inFlight.compute(safe) { _, existing ->
            val current = existing ?: InFlightLock()
            current.users++
            current
        }!!
        return try {
            lock.mutex.withLock {
                if (generation.get() != fetchGeneration) return@withLock null
                cache[safe]?.let { return@withLock it }
                if (safe in negativeCache) return@withLock null
                val preview = withContext(Dispatchers.IO) {
                    runCatching { downloadAndParse(safe) }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.d(TAG, "fetch failed for $safe: ${error.message}")
                        null
                    }
                }
                if (generation.get() != fetchGeneration) return@withLock null
                val useful = preview?.takeIf { LinkPreviewPolicy.isUseful(it) }
                trimCacheIfNeeded()
                if (useful != null) cache[safe] = useful else negativeCache.add(safe)
                useful
            }
        } finally {
            inFlight.computeIfPresent(safe) { _, current ->
                if (current === lock) {
                    if (current.users > 1) {
                        current.users--
                        current
                    } else {
                        null
                    }
                } else {
                    current
                }
            }
        }
    }

    /**
     * 逐跳下载并解析 HTML，显式处理重定向：
     * 1. 每一跳重定向目标必须先经过 [LinkPreviewPolicy.sanitizeUrl] 安全检查；
     * 2. 严禁向内网地址重定向（阻止危险重定向）；
     * 3. 严禁 HTTPS 向 HTTP 协议降级重定向；
     * 4. 严格限制最大重定向次数防止死循环。
     */
    internal fun downloadAndParse(initialUrl: String): LinkPreviewPolicy.Preview? {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "MaodouchatLinkPreview/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val code = response.code

            // 处理 HTTP 重定向响应（301, 302, 303, 307, 308）
            if (code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) return null

                val resolved = runCatching {
                    URI(currentUrl).resolve(location).toString()
                }.getOrNull() ?: return null

                // 核心安全关卡：重定向目标必须先经 sanitizeUrl 审核，阻断内网/私有网段/变体重定向
                val safeRedirect = LinkPreviewPolicy.sanitizeUrl(resolved) ?: run {
                    Log.w(TAG, "Blocked unsafe redirect to $resolved")
                    return null
                }

                // 阻断 HTTPS 向 HTTP 降级重定向
                if (currentUrl.startsWith("https://", ignoreCase = true) &&
                    safeRedirect.startsWith("http://", ignoreCase = true)
                ) {
                    Log.w(TAG, "Blocked HTTPS to HTTP downgrade redirect: $safeRedirect")
                    return null
                }

                currentUrl = safeRedirect
                redirects++
                continue
            }

            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val contentType = body.contentType()?.toString().orEmpty().lowercase()
                if (contentType.isNotBlank() &&
                    !contentType.contains("text/html") &&
                    !contentType.contains("application/xhtml")
                ) {
                    return null
                }
                val source = body.source()
                val buffer = okio.Buffer()
                var remaining = MAX_BYTES.toLong()
                while (remaining > 0 && !source.exhausted()) {
                    val read = source.read(buffer, remaining.coerceAtMost(8192))
                    if (read < 0) break
                    remaining -= read
                }
                val html = buffer.readUtf8()
                if (html.isBlank()) return null
                // og:image 等相对路径按重定向后的最终 URL 解析
                return LinkPreviewPolicy.parseHtmlPreview(currentUrl, html)
            }
        }
        Log.w(TAG, "Exceeded MAX_REDIRECTS ($MAX_REDIRECTS) for $initialUrl")
        return null
    }

    private fun trimCacheIfNeeded() = synchronized(trimMonitor) {
        val size = cache.size + negativeCache.size
        if (size <= CACHE_LIMIT) return@synchronized
        var remaining = size - CACHE_LIMIT + 8
        negativeCache.toList().take(remaining).forEach {
            if (negativeCache.remove(it)) remaining--
        }
        if (remaining > 0) {
            val toRemove = cache.keys.toList().take(remaining)
            toRemove.forEach { cache.remove(it) }
        }
    }
}
