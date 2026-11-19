package com.maodouchat.domain.messaging

import com.maodouchat.core.model.StickerSourcePolicy
import org.json.JSONArray
import org.json.JSONObject

// 贴纸包清单 JSON 解析：包数上限 64、每包贴纸上限 300；id/文件名须通过消毒校验、
// SHA-256 须为 64 位十六进制、URL 相对 baseUrl 解析。
object StickerManifestPolicy {

    const val MAX_MANIFEST_PACKS = 64
    const val MAX_STICKERS_PER_PACK = 300

    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun parseManifest(raw: String, baseUrl: String): List<RemotePack> {
        val root = JSONObject(raw)
        val packs = root.optJSONArray("packs") ?: JSONArray()
        return buildList {
            var packCount = 0
            for (i in 0 until packs.length()) {
                if (packCount >= MAX_MANIFEST_PACKS) break
                val pack = packs.optJSONObject(i) ?: continue
                val id = pack.optString("id").trim()
                if (id.isEmpty() || StickerNamePolicy.sanitizePackId(id) != id) continue
                val stickers = pack.optJSONArray("stickers") ?: JSONArray()
                val items = buildList {
                    var stickerCount = 0
                    for (j in 0 until stickers.length()) {
                        if (stickerCount >= MAX_STICKERS_PER_PACK) break
                        val s = stickers.optJSONObject(j) ?: continue
                        val name = s.optString("name").trim()
                        if (name.isEmpty() || StickerNamePolicy.sanitizeFileName(name) != name) continue
                        val rawUrl = s.optString("url").trim()
                        if (name.isEmpty() || rawUrl.isEmpty()) continue
                        val url = StickerSourcePolicy.resolve(rawUrl, baseUrl) ?: continue
                        val rawSha256 = s.optString("sha256").trim()
                        if (rawSha256.length != 64 || !SHA256_REGEX.matches(rawSha256)) continue
                        add(RemoteSticker(name, url, rawSha256.lowercase()))
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

    fun findPackContaining(packs: List<RemotePack>, fileName: String): RemotePack? =
        packs.firstOrNull { pack -> pack.stickers.any { StickerNamePolicy.sanitizeFileName(it.name) == fileName } }
}
