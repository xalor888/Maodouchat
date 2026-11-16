package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerManifestPolicyTest {

    private val sha = "a".repeat(64)
    private val base = "https://example.com"

    private fun manifest(vararg packs: JSONObject): String =
        JSONObject().put("packs", JSONArray(packs.toList())).toString()

    private fun pack(id: String, vararg stickers: JSONObject): JSONObject {
        val obj = JSONObject().put("id", id)
        if (stickers.isNotEmpty()) obj.put("stickers", JSONArray(stickers.toList()))
        return obj
    }

    private fun sticker(name: String, url: String, sha256: String): JSONObject =
        JSONObject().put("name", name).put("url", url).put("sha256", sha256)

    @Test
    fun `valid manifest resolves relative urls and lowercases sha`() {
        val parsed = StickerManifestPolicy.parseManifest(
            manifest(pack("cute", sticker("cat01.webp", "/static/stickers/cute/cat01.webp", "A".repeat(64)))),
            base
        )
        assertEquals(1, parsed.size)
        assertEquals("cute", parsed[0].id)
        assertEquals("https://example.com/static/stickers/cute/cat01.webp", parsed[0].stickers[0].url)
        assertEquals(sha, parsed[0].stickers[0].sha256)
    }

    @Test
    fun `invalid pack ids and empty packs are skipped`() {
        val parsed = StickerManifestPolicy.parseManifest(
            manifest(
                pack("bad id", sticker("cat01.webp", "/s/cat01.webp", sha)),
                pack("empty"),
                pack("ok", sticker("cat01.webp", "/s/cat01.webp", sha))
            ),
            base
        )
        assertEquals(listOf("ok"), parsed.map { it.id })
    }

    @Test
    fun `bad stickers are skipped but the pack survives`() {
        val parsed = StickerManifestPolicy.parseManifest(
            manifest(
                pack(
                    "cute",
                    sticker("bad/name.webp", "/s/x.webp", sha),
                    sticker("ok.webp", "", sha),
                    sticker("badsha.webp", "/s/y.webp", "xyz"),
                    sticker("good.webp", "/s/good.webp", sha)
                )
            ),
            base
        )
        assertEquals(listOf("good.webp"), parsed.single().stickers.map { it.name })
    }

    @Test
    fun `pack count caps at 64`() {
        val packs = (0 until 70).map { pack("p$it", sticker("a.webp", "/s/a.webp", sha)) }.toTypedArray()
        val parsed = StickerManifestPolicy.parseManifest(manifest(*packs), base)
        assertEquals(64, parsed.size)
        assertEquals("p63", parsed.last().id)
    }

    @Test
    fun `sticker count per pack caps at 300`() {
        val stickers = (0 until 310).map { sticker("s$it.webp", "/s/s$it.webp", sha) }.toTypedArray()
        val parsed = StickerManifestPolicy.parseManifest(manifest(pack("cute", *stickers)), base)
        assertEquals(300, parsed.single().stickers.size)
    }

    @Test
    fun `findPackContaining matches by sanitized file name`() {
        val packs = StickerManifestPolicy.parseManifest(
            manifest(pack("cute", sticker("cat01.webp", "/s/cat01.webp", sha))),
            base
        )
        assertEquals("cute", StickerManifestPolicy.findPackContaining(packs, "cat01.webp")?.id)
        assertNull(StickerManifestPolicy.findPackContaining(packs, "missing.webp"))
        assertTrue(StickerManifestPolicy.findPackContaining(emptyList(), "cat01.webp") == null)
    }
}
