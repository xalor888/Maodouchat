package com.maodouchat.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 本机拉取首屏 HTML 并解析 OG。进程内缓存；失败静默。
 */
object LinkPreviewRepository {
    private const val TAG = "LinkPreview"
    private const val MAX_BYTES = 128_000
    private const val CACHE_LIMIT = 120

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(PublicNetworkDns.create())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, LinkPreviewPolicy.Preview>()
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val generation = AtomicLong(0L)

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
        val mutex = inFlight.getOrPut(safe) { Mutex() }
        return try {
            mutex.withLock {
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
            inFlight.remove(safe, mutex)
        }
    }

    private fun downloadAndParse(url: String): LinkPreviewPolicy.Preview? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MaodouchatLinkPreview/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            // 验证重定向后的最终 URL（防止通过重定向绕过 SSRF 检查）
            if (LinkPreviewPolicy.sanitizeUrl(response.request.url.toString()) == null) return null
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
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
            return LinkPreviewPolicy.parseHtmlPreview(url, html)
        }
    }

    private fun trimCacheIfNeeded() {
        val size = cache.size + negativeCache.size
        if (size <= CACHE_LIMIT) return
        var remaining = size - CACHE_LIMIT + 8
        negativeCache.toList().take(remaining).forEach {
            if (negativeCache.remove(it)) remaining--
        }
        if (remaining > 0) {
            cache.keys.toList().take(remaining).forEach { cache.remove(it) }
        }
    }

}
