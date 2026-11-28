package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ---------- oracle ----------

internal fun visionUrlOracle168(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"

internal fun defaultMimeOracle168(mimeType: String): String = mimeType.ifBlank { "image/jpeg" }

// buildVisionBody restated by hand: text part first with the
// instruction cut to 2000 units, then one image part per image inside
// the limit, each with a defaulted mime; fixed system prompt.
internal fun buildVisionBodyOracle168(
    model: String,
    instruction: String,
    images: List<Pair<String, String>>,
    maxImages: Int
): JSONObject {
    val parts = JSONArray().put(
        JSONObject().put("type", "text").put("text", instruction.take(2_000))
    )
    images.take(maxImages).forEach { (mimeType, imageBase64) ->
        val mime = defaultMimeOracle168(mimeType)
        parts.put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:$mime;base64,$imageBase64"))
        )
    }
    val user = JSONObject().put("role", "user").put("content", parts)
    return JSONObject()
        .put("model", model)
        .put("stream", false)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", "只根据图片作答。不要声称已发送或已删除消息。"))
                .put(user)
        )
}

// Canonical JSON: sorted keys, same escaping as Python json.dumps
// with ensure_ascii=False, so bodies compare independent of key order.
internal fun escJson168(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun canonicalJson168(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> {
        val keys = value.keys().asSequence().toList().sorted()
        keys.joinToString(",", "{", "}") { k -> escJson168(k) + ":" + canonicalJson168(value.get(k)) }
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson168(value.get(it)) }
    is String -> escJson168(value)
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> error("unsupported ${value.javaClass}")
}

// Production calls (cut before oracle extraction; the oracle must
// never call production).
internal fun visionUrlProduction168(baseUrl: String): String =
    AgentVisionRequestPolicy.visionUrl(baseUrl)

internal fun defaultMimeProduction168(mimeType: String): String =
    AgentVisionRequestPolicy.defaultMime(mimeType)

internal fun buildVisionBodyProduction168(
    model: String,
    instruction: String,
    images: List<Pair<String, String>>,
    maxImages: Int
): String = canonicalJson168(
    AgentVisionRequestPolicy.buildVisionBody(model, instruction, images, maxImages)
)

internal fun buildVisionBodyOracleCanonical168(
    model: String,
    instruction: String,
    images: List<Pair<String, String>>,
    maxImages: Int
): String = canonicalJson168(buildVisionBodyOracle168(model, instruction, images, maxImages))

// A body call either returns a canonical body or throws (a negative
// image limit makes List.take throw); compare outcome-or-throw so
// random and sweep cases can include negative limits.
internal fun bodyOutcome168(body: () -> JSONObject): String = try {
    "OK:" + canonicalJson168(body())
} catch (e: Throwable) {
    "THROW:" + e.javaClass.simpleName
}

// ---------- generators ----------

internal data class VisBodyCase168(
    val model: String,
    val instruction: String,
    val images: List<Pair<String, String>>,
    val maxImages: Int
)

internal data class VisUrlCase168(val baseUrl: String)

internal data class VisMimeCase168(val mimeType: String)

internal val VIS_MODEL_POOL168: List<String> = listOf(
    "", "m1", "gpt-4o", "模型", "a b", "x"
)

internal val VIS_INSTRUCTION_POOL168: List<String> = listOf(
    "", "look", "i".repeat(1999), "i".repeat(2000), "i".repeat(2001),
    "i".repeat(2500), "😀".repeat(1000), "😀".repeat(1001),
    "a\nb\"c\\d", "只根据图片作答", "  ", "\u00A0", "\u200B", "x".repeat(3000)
)

internal val VIS_MIME_POOL168: List<String> = listOf(
    "", " ", "image/png", "image/jpeg", "  ", "\u00A0", "image/gif",
    "text/plain", "\n", "a/b", "\u200B"
)

internal val VIS_B64_POOL168: List<String> = listOf(
    "", "aaa", "bbb==", "a b", "x\ny", "é", "😀", "z".repeat(100)
)

internal val VIS_MAX_IMAGES_POOL168: List<Int> = listOf(
    Int.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 100, Int.MAX_VALUE
)

internal val VIS_BASE_POOL168: List<String> = listOf(
    "", "/", "//", "https://x", "https://x/", "https://x//", "https://x///",
    "a/", "a/b/", "https://x/chat/completions", "https://x/chat/completions/",
    "https://x/ ", " "
)

internal fun randomVisBodyCase168(rnd: Random): VisBodyCase168 {
    val n = rnd.nextInt(5)
    val images = List(n) {
        VIS_MIME_POOL168[rnd.nextInt(VIS_MIME_POOL168.size)] to
            VIS_B64_POOL168[rnd.nextInt(VIS_B64_POOL168.size)]
    }
    return VisBodyCase168(
        model = VIS_MODEL_POOL168[rnd.nextInt(VIS_MODEL_POOL168.size)],
        instruction = VIS_INSTRUCTION_POOL168[rnd.nextInt(VIS_INSTRUCTION_POOL168.size)],
        images = images,
        maxImages = VIS_MAX_IMAGES_POOL168[rnd.nextInt(VIS_MAX_IMAGES_POOL168.size)]
    )
}

internal fun randomVisUrlCase168(rnd: Random): VisUrlCase168 =
    VisUrlCase168(baseUrl = VIS_BASE_POOL168[rnd.nextInt(VIS_BASE_POOL168.size)])

internal fun randomVisMimeCase168(rnd: Random): VisMimeCase168 =
    VisMimeCase168(mimeType = VIS_MIME_POOL168[rnd.nextInt(VIS_MIME_POOL168.size)])

internal fun visBodyWireJson168(c: VisBodyCase168): String {
    val sb = StringBuilder()
    sb.append("{\"model\":${escJson168(c.model)},")
    sb.append("\"instruction\":${escJson168(c.instruction)},\"images\":[")
    for ((i, p) in c.images.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append("[${escJson168(p.first)},${escJson168(p.second)}]")
    }
    sb.append("],\"maxImages\":${c.maxImages}}")
    return sb.toString()
}

internal fun visUrlWireJson168(c: VisUrlCase168): String =
    "{\"base\":${escJson168(c.baseUrl)}}"

internal fun visMimeWireJson168(c: VisMimeCase168): String =
    "{\"mime\":${escJson168(c.mimeType)}}"

class AgentVisionRequestPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // visionUrl: only trailing slashes go, anything else stays.
        val urlCases = listOf(
            "https://x/" to "https://x/chat/completions",
            "https://x" to "https://x/chat/completions",
            "https://x//" to "https://x/chat/completions",
            "" to "/chat/completions",
            "/" to "/chat/completions",
            "https://x/ " to "https://x/ /chat/completions"
        )
        for ((base, expected) in urlCases) {
            assertEquals(expected, visionUrlProduction168(base), "visionUrl($base)")
            assertEquals(expected, visionUrlOracle168(base), "visionUrl-oracle($base)")
        }
        // defaultMime: blank falls back; zero-width space is not blank.
        val mimeCases = listOf(
            "image/png" to "image/png",
            "" to "image/jpeg",
            "   " to "image/jpeg",
            "\u00A0" to "image/jpeg",
            "\u200B" to "\u200B"
        )
        for ((mime, expected) in mimeCases) {
            assertEquals(expected, defaultMimeProduction168(mime), "defaultMime($mime)")
            assertEquals(expected, defaultMimeOracle168(mime), "defaultMime-oracle($mime)")
        }
        // Empty images error constant.
        assertEquals("没有可分析的图片", AgentVisionRequestPolicy.EMPTY_IMAGES_ERROR)
        // buildVisionBody: hand-written canonical form for the small cases.
        val small = "{\"messages\":[{\"content\":\"只根据图片作答。不要声称已发送或已删除消息。\",\"role\":\"system\"},{\"content\":[{\"text\":\"look\",\"type\":\"text\"}],\"role\":\"user\"}],\"model\":\"m1\",\"stream\":false}"
        assertEquals(
            small,
            buildVisionBodyProduction168("m1", "look", emptyList(), 4),
            "body-empty-images"
        )
        assertEquals(
            small,
            buildVisionBodyOracleCanonical168("m1", "look", emptyList(), 4),
            "body-empty-images-oracle"
        )
        val oneImage = "{\"messages\":[{\"content\":\"只根据图片作答。不要声称已发送或已删除消息。\",\"role\":\"system\"},{\"content\":[{\"text\":\"look\",\"type\":\"text\"},{\"image_url\":{\"url\":\"data:image/jpeg;base64,aaa\"},\"type\":\"image_url\"}],\"role\":\"user\"}],\"model\":\"m1\",\"stream\":false}"
        assertEquals(
            oneImage,
            buildVisionBodyProduction168("m1", "look", listOf("" to "aaa"), 4),
            "body-one-image-default-mime"
        )
        assertEquals(
            oneImage,
            buildVisionBodyOracleCanonical168("m1", "look", listOf("" to "aaa"), 4),
            "body-one-image-default-mime-oracle"
        )
        // Instruction cut to exactly 2000 units.
        assertEquals(
            "i".repeat(2000),
            AgentVisionRequestPolicy.buildVisionBody("m1", "i".repeat(2500), emptyList(), 4)
                .getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(0).getString("text")
        )
        // A 2001-unit instruction loses its last unit; 2000 stays whole.
        assertEquals(
            2000,
            AgentVisionRequestPolicy.buildVisionBody("m1", "i".repeat(2001), emptyList(), 4)
                .getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(0).getString("text").length
        )
        // 1001 astral chars are 2002 units: the cut keeps 1000 whole pairs.
        assertEquals(
            "😀".repeat(1000),
            AgentVisionRequestPolicy.buildVisionBody("m1", "😀".repeat(1001), emptyList(), 4)
                .getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(0).getString("text")
        )
        // Images cap: 3 images, limit 2 keeps text + 2 image parts.
        val capped = AgentVisionRequestPolicy.buildVisionBody(
            "m1", "look",
            listOf("image/png" to "bbb", "image/gif" to "ccc", "" to "ddd"), 2
        )
        val parts = capped.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals(
            "data:image/gif;base64,ccc",
            parts.getJSONObject(2).getJSONObject("image_url").getString("url")
        )
        // Zero limit keeps only the text part; a negative limit throws
        // (List.take rejects a negative count).
        val zeroBody = AgentVisionRequestPolicy.buildVisionBody(
            "m1", "look", listOf("image/png" to "bbb"), 0
        )
        assertEquals(
            1,
            zeroBody.getJSONArray("messages").getJSONObject(1).getJSONArray("content").length()
        )
        assertFailsWith<IllegalArgumentException> {
            AgentVisionRequestPolicy.buildVisionBody(
                "m1", "look", listOf("image/png" to "bbb"), -1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildVisionBodyOracle168("m1", "look", listOf("image/png" to "bbb"), -1)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(168003)
        repeat(4000) {
            val c = randomVisBodyCase168(rnd)
            assertEquals(
                bodyOutcome168 { buildVisionBodyOracle168(c.model, c.instruction, c.images, c.maxImages) },
                bodyOutcome168 { AgentVisionRequestPolicy.buildVisionBody(c.model, c.instruction, c.images, c.maxImages) },
                "buildVisionBody(random)"
            )
        }
        val rndUrl = Random(168004)
        repeat(1000) {
            val c = randomVisUrlCase168(rndUrl)
            assertEquals(
                visionUrlOracle168(c.baseUrl),
                visionUrlProduction168(c.baseUrl),
                "visionUrl(random)"
            )
        }
        val rndMime = Random(168005)
        repeat(1000) {
            val c = randomVisMimeCase168(rndMime)
            assertEquals(
                defaultMimeOracle168(c.mimeType),
                defaultMimeProduction168(c.mimeType),
                "defaultMime(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Instruction lengths around the 2000 cut, plain and astral.
        for (n in listOf(0, 1, 1999, 2000, 2001, 2500)) {
            val instruction = "i".repeat(n)
            assertEquals(
                buildVisionBodyOracleCanonical168("m1", instruction, emptyList(), 4),
                buildVisionBodyProduction168("m1", instruction, emptyList(), 4),
                "sweep-instruction-len(n=$n)"
            )
        }
        for (n in listOf(999, 1000, 1001)) {
            val instruction = "😀".repeat(n)
            assertEquals(
                buildVisionBodyOracleCanonical168("m1", instruction, emptyList(), 4),
                buildVisionBodyProduction168("m1", instruction, emptyList(), 4),
                "sweep-instruction-astral(n=$n)"
            )
        }
        // Image count limit sweep for a fixed 3-image list; a negative
        // limit throws on both sides, compared as outcome-or-throw.
        val images = listOf("image/png" to "bbb", "image/gif" to "ccc", "" to "ddd")
        for (limit in listOf(-1, 0, 1, 2, 3, 4, 100)) {
            assertEquals(
                bodyOutcome168 { buildVisionBodyOracle168("m1", "look", images, limit) },
                bodyOutcome168 { AgentVisionRequestPolicy.buildVisionBody("m1", "look", images, limit) },
                "sweep-max-images(limit=$limit)"
            )
        }
        // Mime pool x base64 pool inside a one-image body.
        for (mime in VIS_MIME_POOL168) {
            for (b64 in VIS_B64_POOL168) {
                assertEquals(
                    buildVisionBodyOracleCanonical168("m1", "look", listOf(mime to b64), 4),
                    buildVisionBodyProduction168("m1", "look", listOf(mime to b64), 4),
                    "sweep-mime(mime=$mime,b64=$b64)"
                )
            }
        }
        // Trailing-slash counts for visionUrl.
        for (n in 0..5) {
            val base = "https://x" + "/".repeat(n)
            assertEquals(
                visionUrlOracle168(base),
                visionUrlProduction168(base),
                "sweep-url-slashes(n=$n)"
            )
        }
        // Every whitespace unit as a mime: blank or not, both agree.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val mime = u.toChar().toString()
            assertEquals(
                defaultMimeOracle168(mime),
                defaultMimeProduction168(mime),
                "sweep-mime-ws(u=$u)"
            )
        }
    }
}
