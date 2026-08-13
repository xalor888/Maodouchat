package com.maodouchat.slim

import android.content.Context
import android.util.Log
import com.maodouchat.R
import com.maodouchat.network.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按需贴纸包存储（B1 区块 · 包体瘦身与构建基线，2026-08-01）。
 *
 * 目标：贴纸包不再全部内置进 APK，改为从自有服务器按需拉取并缓存到应用私有目录。
 * - 从 `ApiConfig.BASE_URL/api/stickers/manifest.json` 拉取贴纸包清单；
 * - 按需下载贴纸文件到 `filesDir/stickers/<packId>/`（落盘前校验 SHA-256，下载走临时文件+原子重命名）；
 * - LRU 淘汰：超出包数上限或总字节上限时，按最近访问时间淘汰最久未用的包；
 * - 提供 `getSticker(name)`：本地已缓存直接返回，未缓存则查清单并下载后返回；
 * - 内置贴纸裁剪为最小集合：仅保留 1 包「基础表情」（纯 emoji 文本，不占二进制体积）。
 *
 * 既有 `StickerCatalog`（emoji 文本贴纸）为业务功能文件，按 B1 冲突红线未改动；
 * 本存储面向后续图片型贴纸包（PNG/WebP），二者互不冲突。
 *
 * 服务端清单约定（详见 docs/size-baseline.md）：
 * ```
 * GET /api/stickers/manifest.json
 * {
 *   "version": 1,
 *   "packs": [
 *     {
 *       "id": "cute",
 *       "stickers": [
 *         { "name": "cat01.webp", "url": "/static/stickers/cute/cat01.webp", "sha256": "..." }
 *       ]
 *     }
 *   ]
 * }
 * ```
 * `url` 可为相对路径（相对 `ApiConfig.BASE_URL`）。清单获取失败时静默回退到内置基础表情，不影响聊天功能。
 */
object OnDemandStickerStore {

    private const val TAG = "OnDemandStickerStore"
    private const val MANIFEST_ROUTE = "/api/stickers/manifest.json"
    private const val DIR_NAME = "stickers"
    private const val MAX_PACKS = 6
    private const val MAX_TOTAL_BYTES = 24L * 1024L * 1024L
    private const val MANIFEST_TTL_MS = 10 * 60 * 1000L
    private const val MAX_MANIFEST_BYTES = 1_048_576
    private const val MAX_MANIFEST_PACKS = 64
    private const val MAX_STICKERS_PER_PACK = 300

    /** 内置最小集合：1 包「基础表情」（纯 emoji，无需下载）。 */
    const val BUILT_IN_PACK_ID = "basic"

    /** 内置基础表情集合（最小集合，保留 1 包即可覆盖日常高频表情）。 */
    val BUILT_IN_BASIC_STICKERS: List<String> = listOf(
        "😀", "😂", "😍", "😭", "😡", "😴", "🤔", "😎",
        "👍", "👎", "👏", "🙏", "💪", "🤝", "👋", "🫶",
        "❤️", "💔", "🎉", "🎊", "🔥", "💯", "⭐", "✨",
    )

    private data class RemoteSticker(val name: String, val url: String, val sha256: String?)

    private data class RemotePack(val id: String, val stickers: List<RemoteSticker>)

    /** 贴纸包下载结果（含面向 UI 的状态文案，字符串资源见 values/values-en）。 */
    data class StickerPackDownloadResult(
        val packId: String,
        val downloaded: Int,
        val failed: Int,
        val message: String?,
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 8.34：每包下载互斥——getSticker 与 UI 手动下载同一包并发时此前互相覆盖 .part 导致 SHA 失败。 */
    private class PackLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val packLocks = ConcurrentHashMap<String, PackLock>()

    @Volatile
    private var cachedManifest: List<RemotePack> = emptyList()

    @Volatile
    private var manifestFetchedAtMs: Long = 0L

    /** 内置基础表情是否可用（离线兜底，始终 true）。 */
    fun isBuiltIn(name: String): Boolean = name in BUILT_IN_BASIC_STICKERS

    /** 内置基础表情包（最小集合）。 */
    fun builtInBasicPack(): List<String> = BUILT_IN_BASIC_STICKERS

    /** 贴纸包根目录。 */
    fun stickerRoot(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 某个贴纸包的本地目录。 */
    fun packDir(context: Context, packId: String): File =
        File(stickerRoot(context), sanitizePackId(packId)).apply { mkdirs() }

    /** 某个贴纸文件的本地路径（未检查是否存在）。 */
    fun localStickerFile(context: Context, packId: String, name: String): File =
        File(packDir(context, packId), sanitizeFileName(name))

    /** 本地已缓存的所有贴纸文件（跨包）。 */
    fun cachedStickerFiles(context: Context): List<File> =
        stickerRoot(context).listFiles { f -> f.isDirectory }
            ?.flatMap { dir -> dir.listFiles { f -> f.isFile }?.toList().orEmpty() }
            .orEmpty()

    /**
     * 按需取贴纸文件：
     * 1) 本地缓存命中（限定所属包目录，避免跨包同名文件误取）直接返回；
     * 2) 未命中且清单可用则下载所在包后返回；
     * 3) 均不可用返回 null（调用方回退到内置基础表情）。
     * 命中时若清单提供 sha256 则校验，不匹配视为未命中（重新下载）。
     */
    suspend fun getSticker(context: Context, name: String): File? {
        val fileName = sanitizeFileName(name)
        if (fileName.isEmpty()) return null
        val pack = findPackContaining(fileName)
        if (pack != null) {
            val expected = pack.stickers.firstOrNull { sanitizeFileName(it.name) == fileName }
            val local = File(packDir(context, pack.id), fileName)
            if (local.isFile && (expected?.sha256 == null || sha256(local) == expected.sha256)) {
                return local
            }
            val result = ensurePack(context, pack.id)
            if (result.downloaded > 0 || result.failed == 0) {
                val after = File(packDir(context, pack.id), fileName)
                if (after.isFile && (expected?.sha256 == null || sha256(after) == expected.sha256)) {
                    return after
                }
                return null
            }
            return null
        }
        // 清单未加载或未收录该贴纸：退化为跨包模糊查找（保守，无包归属可校验）
        return cachedStickerFiles(context).firstOrNull { it.name == fileName }
    }

    /** 刷新贴纸包清单（带 TTL 与内存缓存，失败静默保留旧清单）。 */
    suspend fun refreshManifest(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedManifest.isNotEmpty() && now - manifestFetchedAtMs < MANIFEST_TTL_MS) {
            return@withContext Result.success(cachedManifest.map { it.id })
        }
        runCatching {
            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}$MANIFEST_ROUTE")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("manifest HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (body.length > MAX_MANIFEST_BYTES) throw IllegalStateException("manifest too large")
                val parsed = parseManifest(body)
                if (parsed.isEmpty()) throw IllegalStateException("manifest empty")
                cachedManifest = parsed
                manifestFetchedAtMs = now
                parsed.map { it.id }
            }
        }
    }

    /**
     * 确保某贴纸包已下载（存在且非空则跳过；否则拉取包内全部贴纸文件）。
     * 下载后执行 LRU 淘汰，返回统计与状态文案。
     */
    suspend fun ensurePack(context: Context, packId: String): StickerPackDownloadResult = withContext(Dispatchers.IO) {
        val safeId = sanitizePackId(packId)
        if (safeId.isEmpty()) {
            return@withContext StickerPackDownloadResult(
                packId, 0, 0, context.getString(R.string.sticker_store_download_failed)
            )
        }
        val pack = (if (cachedManifest.isNotEmpty()) cachedManifest
            else runCatching { parseManifest(fetchManifestRaw()) }.getOrDefault(emptyList()))
            .firstOrNull { it.id == safeId }
        if (pack == null) {
            Log.w(TAG, "ensurePack: 清单中无此包 $safeId")
            return@withContext StickerPackDownloadResult(
                safeId, 0, 0, context.getString(R.string.sticker_store_download_failed)
            )
        }

        val dir = packDir(context, safeId)
        val existing = dir.listFiles { f -> f.isFile }?.toSet().orEmpty()
        if (pack.stickers.all { s -> existing.any { it.name == sanitizeFileName(s.name) && it.length() > 0L } } &&
            pack.stickers.isNotEmpty()
        ) {
            touch(context, safeId)
            return@withContext StickerPackDownloadResult(
                safeId, 0, 0, context.getString(R.string.sticker_store_downloaded)
            )
        }

        // 8.34：整个下载+淘汰流程按包加锁（并发 getSticker / 手动下载不再互相覆盖 .part）
        val lock = packLocks.compute(safeId) { _, existing ->
            (existing ?: PackLock()).also { it.users++ }
        }!!
        try {
            lock.mutex.withLock {
                downloadPackLocked(context, safeId, pack)
            }
        } finally {
            packLocks.computeIfPresent(safeId) { _, current ->
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

    private suspend fun downloadPackLocked(
        context: Context,
        safeId: String,
        pack: RemotePack
    ): StickerPackDownloadResult = withContext(Dispatchers.IO) {
        var downloaded = 0
        var failed = 0
        for (sticker in pack.stickers) {
            val target = localStickerFile(context, safeId, sticker.name)
            if (target.isFile && target.length() > 0L) {
                if (sticker.sha256 == null || sha256(target) == sticker.sha256) {
                    downloaded++
                    continue
                }
                target.delete()
            }
            val url = if (sticker.url.startsWith("http://") || sticker.url.startsWith("https://")) {
                sticker.url
            } else {
                ApiConfig.BASE_URL.trimEnd('/') + "/" + sticker.url.trimStart('/')
            }
            if (downloadFile(url, target, sticker.sha256)) downloaded++ else failed++
        }
        evict(context)
        val message = if (failed == 0) {
            context.getString(R.string.sticker_store_downloaded)
        } else if (downloaded > 0) {
            context.getString(R.string.sticker_store_partial, downloaded, failed)
        } else {
            context.getString(R.string.sticker_store_download_failed)
        }
        StickerPackDownloadResult(safeId, downloaded, failed, message)
    }

    /**
     * LRU 淘汰：包总数超过 [MAX_PACKS] 或总字节数超过 [MAX_TOTAL_BYTES] 时，
     * 按目录最近访问时间（lastModified）淘汰最久未用的包。
     */
    fun evict(context: Context, maxPacks: Int = MAX_PACKS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val root = stickerRoot(context)
        val dirs = root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        var total = dirs.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }
        val byAge = dirs.sortedBy { it.lastModified() } // 旧 → 新
        var removable = byAge.toMutableList()
        while (removable.size > maxPacks || total > maxTotalBytes) {
            val oldest = removable.removeFirstOrNull() ?: break
            val size = oldest.listFiles()?.sumOf { it.length() } ?: 0L
            oldest.deleteRecursively()
            total -= size
        }
    }

    /** 记录包最近使用时间（LRU 依据）。 */
    fun touch(context: Context, packId: String) {
        val dir = packDir(context, sanitizePackId(packId))
        if (dir.isDirectory) dir.setLastModified(System.currentTimeMillis())
    }

    // ---- 内部实现 ----

    private fun findPackContaining(fileName: String): RemotePack? =
        cachedManifest.firstOrNull { pack -> pack.stickers.any { sanitizeFileName(it.name) == fileName } }

    private fun fetchManifestRaw(): String {
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}$MANIFEST_ROUTE")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("manifest HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.length > MAX_MANIFEST_BYTES) throw IllegalStateException("manifest too large")
            body
        }
    }

    private fun parseManifest(raw: String): List<RemotePack> {
        val root = JSONObject(raw)
        val packs = root.optJSONArray("packs") ?: JSONArray()
        return buildList {
            var packCount = 0
            for (i in 0 until packs.length()) {
                if (packCount >= MAX_MANIFEST_PACKS) break
                val pack = packs.optJSONObject(i) ?: continue
                val id = pack.optString("id").trim()
                if (id.isEmpty()) continue
                val stickers = pack.optJSONArray("stickers") ?: JSONArray()
                val items = buildList {
                    var stickerCount = 0
                    for (j in 0 until stickers.length()) {
                        if (stickerCount >= MAX_STICKERS_PER_PACK) break
                        val s = stickers.optJSONObject(j) ?: continue
                        val name = s.optString("name").trim()
                        val url = s.optString("url").trim()
                        if (name.isEmpty() || url.isEmpty()) continue
                        add(RemoteSticker(name, url, s.optString("sha256").trim().ifBlank { null }))
                        stickerCount++
                    }
                }
                if (items.isNotEmpty()) {
                    add(RemotePack(id, items))
                    packCount++
                }
            }
        }
    }

    private fun downloadFile(url: String, target: File, expectedSha256: String?): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty body")
            // 8.34：.part 加随机后缀——固定名在并发下载同一文件时互相覆盖导致 SHA 校验失败
            //（包级互斥已覆盖同包并发，跨包同名文件路径不存在，此处再兜底一层）
            val tmp = File(target.parentFile, target.name + ".part." + UUID.randomUUID().toString().take(8))
            try {
                body.byteStream().use { input -> tmp.outputStream().buffered().use { output -> input.copyTo(output) } }
                if (tmp.length() <= 0L) throw IllegalStateException("empty file")
                if (expectedSha256 != null && sha256(tmp) != expectedSha256) {
                    throw IllegalStateException("SHA-256 mismatch")
                }
                if (!tmp.renameTo(target)) {
                    if (!target.isFile) throw IllegalStateException("write failed")
                    tmp.delete()
                }
            } finally {
                if (tmp.exists() && !target.isFile) tmp.delete()
            }
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "downloadFile 失败: $url", error)
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizePackId(id: String): String =
        id.trim().replace(Regex("[^A-Za-z0-9_-]"), "").take(40)

    private fun sanitizeFileName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "").take(80)
}
