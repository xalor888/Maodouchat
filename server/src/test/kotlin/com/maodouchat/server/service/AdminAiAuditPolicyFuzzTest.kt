package com.maodouchat.server.service

import com.maodouchat.server.model.AiUsageAdminResponse
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// estimatedTokensFromInputChars restated by hand: clamp the input at
// zero, then (chars + 3) / 4 with Int wrap-around, at least 1 for a
// non-empty input. The +3 can overflow near Int.MAX_VALUE, which is
// why the division is truncating, not flooring.
internal fun estimatedTokensFromInputCharsOracle178(chars: Int): Int {
    val safe = chars.coerceAtLeast(0)
    if (safe == 0) return 0
    return ((safe + 3) / 4).coerceAtLeast(1)
}

// resolveEstimatedTokens restated by hand: real provider tokens win
// when either side is present; the Long sum can wrap, so it is
// clamped into [0, Int.MAX_VALUE] before narrowing. Both null falls
// back to the char-count estimate.
internal fun resolveEstimatedTokensOracle178(
    inputTokens: Long?,
    outputTokens: Long?,
    inputChars: Int,
): Int {
    val chars = inputChars.coerceAtLeast(0)
    return if (inputTokens != null || outputTokens != null) {
        ((inputTokens ?: 0L) + (outputTokens ?: 0L))
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    } else {
        estimatedTokensFromInputCharsOracle178(chars)
    }
}

// normalizeLimit restated by hand: null means the default, then clamp
// into [1, MAX_LIMIT].
internal fun normalizeLimitOracle178(raw: Int?): Int =
    (raw ?: 60).coerceIn(1, 250)

// normalizeOffset restated by hand: null means zero, negatives clamp
// to zero.
internal fun normalizeOffsetOracle178(raw: Long?): Long =
    (raw ?: 0L).coerceAtLeast(0L)

// normalizeFeatureFilter restated by hand: trim, take 40 units, drop
// blanks and the literal "ALL" (case-sensitive).
internal fun normalizeFeatureFilterOracle178(raw: String?): String? =
    raw?.trim()?.take(40)?.takeIf { it.isNotBlank() && it != "ALL" }

// normalizeUserFilter restated by hand: trim, take 50 units, drop
// blanks.
internal fun normalizeUserFilterOracle178(raw: String?): String? =
    raw?.trim()?.take(50)?.takeIf { it.isNotBlank() }

// Forbidden admin payload keys, restated from the production set.
internal val FORBIDDEN_PAYLOAD_KEYS178: Set<String> = setOf(
    "prompt", "body", "text", "content", "messages",
    "chatId", "chat_id", "rewrite", "summary", "transcript",
)

// isMetadataOnlyResponse restated by hand: no key may be forbidden.
internal fun isMetadataOnlyResponseOracle178(keys: Set<String>): Boolean =
    keys.none { it in FORBIDDEN_PAYLOAD_KEYS178 }

// toAdminResponse restated by hand: every text field is truncated to
// its cap in UTF-16 units (no trim), counts and durations clamp at
// zero, and the token estimate resolves through the same rule above.
internal fun toAdminResponseOracle178(
    id: String,
    userId: String,
    feature: String,
    model: String?,
    status: String,
    inputChars: Int,
    contextMessages: Int,
    durationMs: Long?,
    error: String?,
    createdAt: Long,
    inputTokens: Long? = null,
    outputTokens: Long? = null,
): AiUsageAdminResponse {
    val safeInput = inputChars.coerceAtLeast(0)
    return AiUsageAdminResponse(
        id = id.take(100),
        userId = userId.take(50),
        feature = feature.take(40),
        model = model?.take(80),
        status = status.take(30),
        inputChars = safeInput,
        contextMessages = contextMessages.coerceAtLeast(0),
        durationMs = durationMs?.coerceAtLeast(0L),
        error = error?.take(280),
        createdAt = createdAt,
        estimatedTokens = resolveEstimatedTokensOracle178(inputTokens, outputTokens, safeInput),
    )
}

// Production estimatedTokensFromInputChars estimates tokens from chars.
internal fun estimatedTokensFromInputCharsProduction178(chars: Int): Int =
    AdminAiAuditPolicy.estimatedTokensFromInputChars(chars)

// Production resolveEstimatedTokens prefers real provider tokens.
internal fun resolveEstimatedTokensProduction178(
    inputTokens: Long?,
    outputTokens: Long?,
    inputChars: Int,
): Int = AdminAiAuditPolicy.resolveEstimatedTokens(inputTokens, outputTokens, inputChars)

// Production normalizeLimit clamps the admin list limit.
internal fun normalizeLimitProduction178(raw: Int?): Int =
    AdminAiAuditPolicy.normalizeLimit(raw)

// Production normalizeOffset clamps the admin list offset.
internal fun normalizeOffsetProduction178(raw: Long?): Long =
    AdminAiAuditPolicy.normalizeOffset(raw)

// Production normalizeFeatureFilter normalizes the feature filter.
internal fun normalizeFeatureFilterProduction178(raw: String?): String? =
    AdminAiAuditPolicy.normalizeFeatureFilter(raw)

// Production normalizeUserFilter normalizes the user filter.
internal fun normalizeUserFilterProduction178(raw: String?): String? =
    AdminAiAuditPolicy.normalizeUserFilter(raw)

// Production isMetadataOnlyResponse checks the payload keys.
internal fun isMetadataOnlyResponseProduction178(keys: Set<String>): Boolean =
    AdminAiAuditPolicy.isMetadataOnlyResponse(keys)

// Production toAdminResponse projects an audit row into the admin DTO.
internal fun toAdminResponseProduction178(
    id: String,
    userId: String,
    feature: String,
    model: String?,
    status: String,
    inputChars: Int,
    contextMessages: Int,
    durationMs: Long?,
    error: String?,
    createdAt: Long,
    inputTokens: Long? = null,
    outputTokens: Long? = null,
): AiUsageAdminResponse = AdminAiAuditPolicy.toAdminResponse(
    id, userId, feature, model, status, inputChars, contextMessages,
    durationMs, error, createdAt, inputTokens, outputTokens,
)

// ---------- generators ----------

internal data class AdminAuditCase178(
    val inputChars: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val rawLimit: Int?,
    val rawOffset: Long?,
    val featureRaw: String?,
    val userRaw: String?,
    val id: String,
    val userId: String,
    val feature: String,
    val model: String?,
    val status: String,
    val contextMessages: Int,
    val durationMs: Long?,
    val error: String?,
    val createdAt: Long,
    val keys: List<String>,
)

internal val ADMIN_INT_POOL178: List<Int> = listOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100, -1, 0, 1, 2, 3, 4, 5, 7, 8,
    59, 60, 61, 99, 100, 101, 249, 250, 251, 9999,
    Int.MAX_VALUE - 3, Int.MAX_VALUE - 2, Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal val ADMIN_LONG_POOL178: List<Long> = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -2_147_483_648L, -1L, 0L, 1L, 2L,
    2_147_483_647L, 2_147_483_648L, 4_294_967_295L, 8_589_934_590L,
    Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal val ADMIN_FILTER_POOL178: List<String?> = listOf(
    null, "", " ", "  ", "ALL", " ALL ", "all", "All", "rewrite", " rewrite ",
    "a".repeat(39), "a".repeat(40), "a".repeat(41), "a".repeat(50), "a".repeat(51),
    "ALLx", "xALL", "ALL\u00a0", "\u00a0ALL\u00a0", "a\u0085b", "\u0085rewrite\u0085",
    "feature with spaces", "x".repeat(200), "İ", "ß", "ﬀ", "chat",
)

internal val ADMIN_ID_POOL178: List<String> = listOf(
    "", " ", "ai_1", "u1", "  u1  ", "rewrite", "gpt-test", "success",
    "a".repeat(29), "a".repeat(30), "a".repeat(31),
    "a".repeat(39), "a".repeat(40), "a".repeat(41),
    "a".repeat(49), "a".repeat(50), "a".repeat(51),
    "a".repeat(79), "a".repeat(80), "a".repeat(81),
    "a".repeat(99), "a".repeat(100), "a".repeat(101),
    "a".repeat(279), "a".repeat(280), "a".repeat(281),
    "ü".repeat(60), "a\uD83D\uDE00b", "x\ny", "x\u00a0y", "  padded  ",
)

internal val ADMIN_KEY_POOL178: List<String> = listOf(
    "id", "userId", "feature", "model", "status", "inputChars",
    "contextMessages", "durationMs", "error", "createdAt", "estimatedTokens",
    "prompt", "body", "text", "content", "messages", "chatId", "chat_id",
    "rewrite", "summary", "transcript",
    "Prompt", "CHATID", "chatid", "prompt ", " prompt", "chatId ",
)

internal fun randomAdminAuditCase178(rnd: Random): AdminAuditCase178 {
    fun randomText(): String {
        if (rnd.nextInt(3) == 0) {
            return ADMIN_ID_POOL178[rnd.nextInt(ADMIN_ID_POOL178.size)]
        }
        val alphabet = "abcXYZ019 _-./\t\n\u00a0\u0085\u2003"
        val len = rnd.nextInt(0, 60)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    fun randomFilter(): String? {
        if (rnd.nextInt(4) == 0) return null
        if (rnd.nextInt(2) == 0) {
            return ADMIN_FILTER_POOL178[rnd.nextInt(ADMIN_FILTER_POOL178.size)]
        }
        return randomText()
    }
    fun randomLong(): Long? {
        if (rnd.nextInt(3) == 0) return null
        return if (rnd.nextInt(2) == 0) {
            ADMIN_LONG_POOL178[rnd.nextInt(ADMIN_LONG_POOL178.size)]
        } else {
            rnd.nextLong(-3_000_000_000L, 3_000_000_000L)
        }
    }
    fun randomInt(): Int {
        return if (rnd.nextInt(2) == 0) {
            ADMIN_INT_POOL178[rnd.nextInt(ADMIN_INT_POOL178.size)]
        } else {
            rnd.nextInt(-1000, 10000)
        }
    }
    val nKeys = rnd.nextInt(0, 6)
    val keys = ArrayList<String>()
    repeat(nKeys) { keys.add(ADMIN_KEY_POOL178[rnd.nextInt(ADMIN_KEY_POOL178.size)]) }
    return AdminAuditCase178(
        inputChars = randomInt(),
        inputTokens = randomLong(),
        outputTokens = randomLong(),
        rawLimit = if (rnd.nextInt(3) == 0) null else randomInt(),
        rawOffset = randomLong(),
        featureRaw = randomFilter(),
        userRaw = randomFilter(),
        id = randomText(),
        userId = randomText(),
        feature = randomText(),
        model = if (rnd.nextInt(3) == 0) null else randomText(),
        status = randomText(),
        contextMessages = randomInt(),
        durationMs = randomLong(),
        error = if (rnd.nextInt(3) == 0) null else randomText(),
        createdAt = if (rnd.nextInt(2) == 0) {
            ADMIN_LONG_POOL178[rnd.nextInt(ADMIN_LONG_POOL178.size)]
        } else {
            rnd.nextLong(-1_000_000_000_000L, 2_000_000_000_000L)
        },
        keys = keys,
    )
}

internal fun escJsonAdmin178(s: String): String {
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

internal fun escJsonOrNullAdmin178(s: String?): String =
    if (s == null) "null" else escJsonAdmin178(s)

internal fun escJsonOrNullLongAdmin178(v: Long?): String =
    if (v == null) "null" else v.toString()

internal fun escJsonStringListAdmin178(items: List<String>): String =
    "[" + items.joinToString(",") { escJsonAdmin178(it) } + "]"

internal fun adminAuditWireJson178(c: AdminAuditCase178): String =
    "{\"inputChars\":${c.inputChars}," +
        "\"inputTokens\":${escJsonOrNullLongAdmin178(c.inputTokens)}," +
        "\"outputTokens\":${escJsonOrNullLongAdmin178(c.outputTokens)}," +
        "\"rawLimit\":${c.rawLimit?.toString() ?: "null"}," +
        "\"rawOffset\":${escJsonOrNullLongAdmin178(c.rawOffset)}," +
        "\"featureRaw\":${escJsonOrNullAdmin178(c.featureRaw)}," +
        "\"userRaw\":${escJsonOrNullAdmin178(c.userRaw)}," +
        "\"id\":${escJsonAdmin178(c.id)}," +
        "\"userId\":${escJsonAdmin178(c.userId)}," +
        "\"feature\":${escJsonAdmin178(c.feature)}," +
        "\"model\":${escJsonOrNullAdmin178(c.model)}," +
        "\"status\":${escJsonAdmin178(c.status)}," +
        "\"contextMessages\":${c.contextMessages}," +
        "\"durationMs\":${escJsonOrNullLongAdmin178(c.durationMs)}," +
        "\"error\":${escJsonOrNullAdmin178(c.error)}," +
        "\"createdAt\":${c.createdAt}," +
        "\"keys\":${escJsonStringListAdmin178(c.keys)}}"

internal fun adminAuditResponseJson178(r: AiUsageAdminResponse): String =
    "{\"id\":${escJsonAdmin178(r.id)}," +
        "\"userId\":${escJsonAdmin178(r.userId)}," +
        "\"feature\":${escJsonAdmin178(r.feature)}," +
        "\"model\":${escJsonOrNullAdmin178(r.model)}," +
        "\"status\":${escJsonAdmin178(r.status)}," +
        "\"inputChars\":${r.inputChars}," +
        "\"contextMessages\":${r.contextMessages}," +
        "\"durationMs\":${escJsonOrNullLongAdmin178(r.durationMs)}," +
        "\"error\":${escJsonOrNullAdmin178(r.error)}," +
        "\"createdAt\":${r.createdAt}," +
        "\"estimatedTokens\":${r.estimatedTokens}}"

internal fun adminAuditProjectionJson178(
    estimated: Int,
    resolved: Int,
    limit: Int,
    offset: Long,
    featureFilter: String?,
    userFilter: String?,
    response: AiUsageAdminResponse,
    metadataOnly: Boolean,
): String =
    "{\"estimated\":$estimated," +
        "\"resolved\":$resolved," +
        "\"limit\":$limit," +
        "\"offset\":$offset," +
        "\"featureFilter\":${escJsonOrNullAdmin178(featureFilter)}," +
        "\"userFilter\":${escJsonOrNullAdmin178(userFilter)}," +
        "\"response\":${adminAuditResponseJson178(response)}," +
        "\"metadataOnly\":$metadataOnly}"

class AdminAiAuditPolicyFuzzTest {

    private fun checkAdminAudit178(c: AdminAuditCase178) {
        val keys = c.keys.toSet()
        assertEquals(
            adminAuditProjectionJson178(
                estimatedTokensFromInputCharsOracle178(c.inputChars),
                resolveEstimatedTokensOracle178(c.inputTokens, c.outputTokens, c.inputChars),
                normalizeLimitOracle178(c.rawLimit),
                normalizeOffsetOracle178(c.rawOffset),
                normalizeFeatureFilterOracle178(c.featureRaw),
                normalizeUserFilterOracle178(c.userRaw),
                toAdminResponseOracle178(
                    c.id, c.userId, c.feature, c.model, c.status, c.inputChars,
                    c.contextMessages, c.durationMs, c.error, c.createdAt,
                    c.inputTokens, c.outputTokens,
                ),
                isMetadataOnlyResponseOracle178(keys),
            ),
            adminAuditProjectionJson178(
                estimatedTokensFromInputCharsProduction178(c.inputChars),
                resolveEstimatedTokensProduction178(c.inputTokens, c.outputTokens, c.inputChars),
                normalizeLimitProduction178(c.rawLimit),
                normalizeOffsetProduction178(c.rawOffset),
                normalizeFeatureFilterProduction178(c.featureRaw),
                normalizeUserFilterProduction178(c.userRaw),
                toAdminResponseProduction178(
                    c.id, c.userId, c.feature, c.model, c.status, c.inputChars,
                    c.contextMessages, c.durationMs, c.error, c.createdAt,
                    c.inputTokens, c.outputTokens,
                ),
                isMetadataOnlyResponseProduction178(keys),
            ),
            "audit($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Token estimates at the Int/Long wrap edges; limits and
        // offsets at their clamps; filters at the blank/ALL edges.
        for (chars in ADMIN_INT_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    chars, null, null, null, null, null, null,
                    "ai_1", "u1", "rewrite", "gpt-test", "success", 3, 80L, null,
                    1_700_000_000_000L, listOf("id", "feature"),
                )
            )
        }
        for (inputTokens in ADMIN_LONG_POOL178) {
            for (outputTokens in listOf<Long?>(null, 0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                checkAdminAudit178(
                    AdminAuditCase178(
                        120, inputTokens, outputTokens, null, null, null, null,
                        "ai_1", "u1", "rewrite", "gpt-test", "success", 3, 80L, null,
                        1_700_000_000_000L, listOf("id"),
                    )
                )
            }
        }
        for (rawLimit in ADMIN_INT_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, rawLimit, null, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (rawOffset in ADMIN_LONG_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, null, rawOffset, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (filter in ADMIN_FILTER_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, null, null, filter, filter,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (text in ADMIN_ID_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, null, null, null, null,
                    text, text, text, text, text, -5, -10L, text,
                    1_700_000_000_000L, listOf("id", "prompt"),
                )
            )
        }
        for (key in ADMIN_KEY_POOL178) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, null, null, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf(key),
                )
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(178001)
        repeat(4000) { checkAdminAudit178(randomAdminAuditCase178(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every char count 0..300 (estimate step), every limit 0..300,
        // and string lengths 0..320 across the truncation caps.
        for (chars in 0..300) {
            checkAdminAudit178(
                AdminAuditCase178(
                    chars, null, null, null, null, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (rawLimit in -10..300) {
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, rawLimit, null, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (len in 0..320) {
            val text = "a".repeat(len)
            checkAdminAudit178(
                AdminAuditCase178(
                    120, null, null, null, null, null, null,
                    text, text, text, text, text, 3, null, text,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
        for (chars in listOf(Int.MAX_VALUE - 7, Int.MAX_VALUE - 6, Int.MAX_VALUE - 5, Int.MAX_VALUE - 4)) {
            checkAdminAudit178(
                AdminAuditCase178(
                    chars, null, null, null, null, null, null,
                    "ai_1", "u1", "rewrite", null, "success", 3, null, null,
                    1_700_000_000_000L, listOf("id"),
                )
            )
        }
    }
}
