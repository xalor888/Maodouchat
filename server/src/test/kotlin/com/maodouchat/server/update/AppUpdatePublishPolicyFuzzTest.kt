package com.maodouchat.server.update

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// tokenConfigured restated by hand: the trimmed token needs at least
// 16 UTF-16 units.
internal fun tokenConfiguredOracle178(token: String): Boolean =
    token.trim().length >= 16

// tokensMatch restated by hand: the expected token must be configured
// and the provided one non-blank; then the trimmed UTF-8 bytes must
// match exactly. A lone surrogate encodes as '?' on both sides, so a
// surrogate token can match a literal '?' token.
internal fun tokensMatchOracle178(expected: String, provided: String): Boolean {
    if (!tokenConfiguredOracle178(expected) || provided.isBlank()) return false
    val a = expected.trim().toByteArray(Charsets.UTF_8)
    val b = provided.trim().toByteArray(Charsets.UTF_8)
    if (a.size != b.size) return false
    return a.contentEquals(b)
}

// parseVersionCode restated by hand: trim, then a strict integer
// parse (optional sign, digits only, Int range); zero or negative is
// not a version.
internal fun parseVersionCodeOracle178(raw: String?): Int? {
    val value = raw?.trim()?.toIntOrNull() ?: return null
    if (value <= 0) return null
    return value
}

// isDowngrade restated by hand: equal counts as a downgrade.
internal fun isDowngradeOracle178(newVersionCode: Int, currentVersionCode: Int): Boolean =
    newVersionCode <= currentVersionCode

internal val VERSION_NAME_PATTERN178 = Regex("^[A-Za-z0-9._+-]+$")

// parseVersionName restated by hand: trim, at most 32 units, and the
// whole value must match the ASCII version-name pattern.
internal fun parseVersionNameOracle178(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.length > 32) return null
    if (!value.matches(VERSION_NAME_PATTERN178)) return null
    return value
}

// urlDecode restated by hand (no URLDecoder call): '+' becomes a
// space, runs of %XX bytes are decoded as UTF-8, anything else is
// literal. A malformed % escape throws, exactly like URLDecoder.
// The two hex chars are parsed like Integer.parseInt: an ASCII sign
// (+/-) plus one hex digit counts (so "%+0" is byte 0, "%+F" is byte
// 0x0F), and hex digits are Character.digit, so Unicode decimal
// digits and full-width A-F count. URLDecoder throws on a negative
// parseInt result, so "%-0" is byte 0 but any other "%-D" throws.
internal fun urlDecodeOracle178(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> {
                out.append(' ')
                i++
            }
            c == '%' -> {
                val bytes = ArrayList<Byte>()
                var j = i
                while (j < s.length && s[j] == '%') {
                    if (j + 2 >= s.length) throw IllegalArgumentException("bad % escape")
                    val first = s[j + 1]
                    val second = s[j + 2]
                    val value = if (first == '+' || first == '-') {
                        val d = second.digitToIntOrNull(16)
                            ?: throw IllegalArgumentException("bad % escape")
                        if (first == '-') {
                            // URLDecoder throws on a negative parseInt
                            // result: "%-0" is byte 0, any other "%-D" throws.
                            if (d != 0) throw IllegalArgumentException("bad % escape")
                            0
                        } else {
                            d
                        }
                    } else {
                        val hi = first.digitToIntOrNull(16)
                            ?: throw IllegalArgumentException("bad % escape")
                        val lo = second.digitToIntOrNull(16)
                            ?: throw IllegalArgumentException("bad % escape")
                        hi * 16 + lo
                    }
                    bytes.add(value.toByte())
                    j += 3
                }
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                i = j
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

// sanitizeNotes restated by hand: blank input is empty; a '%' runs
// the URL decoder (malformed escapes fall back to the trimmed input);
// Latin-1-range chars are re-decoded as UTF-8 and kept only when no
// replacement character appears; the result is trimmed and capped at
// 2000 units.
internal fun sanitizeNotesOracle178(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim()
    val decoded = try {
        if (trimmed.contains('%')) {
            urlDecodeOracle178(trimmed)
        } else if (trimmed.any { it.code in 0x80..0xFF }) {
            val bytes = trimmed.toByteArray(Charsets.ISO_8859_1)
            val utf8Str = String(bytes, Charsets.UTF_8)
            if (utf8Str.none { it == '\uFFFD' }) utf8Str else trimmed
        } else {
            trimmed
        }
    } catch (_: Exception) {
        trimmed
    }
    return decoded.trim().take(2_000)
}

// isZipMagic restated by hand: PK + one of the three ZIP signatures.
internal fun isZipMagicOracle178(header: ByteArray): Boolean {
    if (header.size < 4) return false
    return header[0] == 0x50.toByte() &&
        header[1] == 0x4B.toByte() &&
        (
            (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
            )
}

// publicApkUrl restated by hand: trailing slashes go, then the fixed
// public path is appended.
internal fun publicApkUrlOracle178(baseUrl: String): String =
    "${baseUrl.trimEnd('/')}/api/public/app-update/latest.apk"

// bearerToken restated by hand: the "Bearer " prefix matches
// case-insensitively; the remainder is trimmed and must be non-empty.
internal fun bearerTokenOracle178(authorization: String?): String? {
    val value = authorization?.trim().orEmpty()
    if (!value.startsWith("Bearer ", ignoreCase = true)) return null
    return value.substring(7).trim().takeIf { it.isNotEmpty() }
}

// Production tokenConfigured checks the token length.
internal fun tokenConfiguredProduction178(token: String): Boolean =
    AppUpdatePublishPolicy.tokenConfigured(token)

// Production tokensMatch compares tokens in constant time.
internal fun tokensMatchProduction178(expected: String, provided: String): Boolean =
    AppUpdatePublishPolicy.tokensMatch(expected, provided)

// Production parseVersionCode parses the version code.
internal fun parseVersionCodeProduction178(raw: String?): Int? =
    AppUpdatePublishPolicy.parseVersionCode(raw)

// Production isDowngrade rejects equal or lower versions.
internal fun isDowngradeProduction178(newVersionCode: Int, currentVersionCode: Int): Boolean =
    AppUpdatePublishPolicy.isDowngrade(newVersionCode, currentVersionCode)

// Production parseVersionName parses the version name.
internal fun parseVersionNameProduction178(raw: String?): String? =
    AppUpdatePublishPolicy.parseVersionName(raw)

// Production sanitizeNotes cleans release notes for display.
internal fun sanitizeNotesProduction178(raw: String?): String =
    AppUpdatePublishPolicy.sanitizeNotes(raw)

// Production isZipMagic checks the ZIP header.
internal fun isZipMagicProduction178(header: ByteArray): Boolean =
    AppUpdatePublishPolicy.isZipMagic(header)

// Production publicApkUrl builds the public APK URL.
internal fun publicApkUrlProduction178(baseUrl: String): String =
    AppUpdatePublishPolicy.publicApkUrl(baseUrl)

// Production bearerToken extracts the bearer token.
internal fun bearerTokenProduction178(authorization: String?): String? =
    AppUpdatePublishPolicy.bearerToken(authorization)

// ---------- generators ----------

internal data class UpdateCase178(
    val tokenExpected: String,
    val tokenProvided: String,
    val versionCodeRaw: String?,
    val newVersion: Int,
    val currentVersion: Int,
    val versionNameRaw: String?,
    val notesRaw: String?,
    val header: List<Int>,
    val baseUrl: String,
    val authorization: String?,
)

internal val UPDATE_TOKEN_POOL178: List<String> = listOf(
    "", "short", "sixteen-chars-ok", "sixteen-chars-no",
    "  sixteen-chars-ok  ", "0123456789abcdef",
    "a".repeat(15), "a".repeat(16), "a".repeat(17),
    "\u00e9".repeat(16), "a\uD800b" + "c".repeat(13), "a?b" + "c".repeat(13),
    "token with spaces inside ok", "Bearer <redacted>", "sixteen-chars-ok\n",
    "\u00a0sixteen-chars-ok\u00a0", "a\u0085" + "b".repeat(15),
    "sixteen-chars-ok\u2003", "\u2003sixteen-chars-ok",
)

internal val UPDATE_VERSION_CODE_POOL178: List<String?> = listOf(
    null, "", " ", "0", "-1", "18", "+18", "007", "2147483647", "2147483648",
    "-2147483648", "1_000", "18 ", " 18", "0x10", "9999999999", "1.5",
    "\uff11\uff18", "+", "-", "00", "-0", "+0", "2147483646",
    "\u0661\u0662\u0663", "\u0967\u0968\u0969", "+\uff11\uff18", "-\uff11\uff18",
)

internal val UPDATE_VERSION_NAME_POOL178: List<String?> = listOf(
    null, "", " ", "1.2.3", "v1-beta_2", "has space", "\u00e9",
    "a".repeat(32), "a".repeat(33), "-._+", "1,2", "a/b", " 1.2.3 ",
    "1.2.3\n", "a".repeat(100), "1.2.3\u00a0", "\u00a01.2.3",
)

internal val UPDATE_NOTES_POOL178: List<String?> = listOf(
    null, "", " ", "abc", "  abc  ", "a+b", "%41", "%C3%A9", "caf%C3%A9",
    "%", "%zz", "%4", "100%25", "caf\u00e9", "\u00e9\u00e8", "a\u0100b",
    "%E2%82%AC", "%FF", "%00", "a%2Fb", "%u1234", "a\nb", "%20%20",
    "%%41", "%41%42", "line1\nline2", "a\uD800b", "%ED%A0%80",
    "%41%4", "%41%", "%G0", "%2G", "%C3%28", "%2525", "%F0%9F%98%80",
    "x".repeat(1999), "x".repeat(2000), "x".repeat(2001), "x".repeat(3000),
    "  %20abc%20  ", "caf\u00e9%20x", "\u4e2d\u6587", "a%2Bb",
    "%+0", "%-0", "%+F", "%-F", "%+41", "%-41", "%\uff14\uff11", "%+\uff26",
)

internal val UPDATE_HEADER_POOL178: List<List<Int>> = listOf(
    listOf(), listOf(0x50), listOf(0x50, 0x4B, 0x03, 0x04),
    listOf(0x50, 0x4B, 0x05, 0x06), listOf(0x50, 0x4B, 0x07, 0x08),
    listOf(0x50, 0x4B, 0x03, 0x05), listOf(0, 0, 0, 0),
    listOf(0x50, 0x4B, 0x03, 0x04, 0xFF), listOf(0x50, 0x4B),
    listOf(0x50, 0x4B, 0x03), listOf(0x51, 0x4B, 0x03, 0x04),
    listOf(0x50, 0x4C, 0x03, 0x04), listOf(0x50, 0x4B, 0x04, 0x04),
)

internal val UPDATE_URL_POOL178: List<String> = listOf(
    "", "https://chat.example.com", "https://chat.example.com/",
    "https://chat.example.com///", "http://x", "/", "  https://x/  ",
    "https://x/a/b", "x", "https://x?",
)

internal val UPDATE_AUTH_POOL178: List<String?> = listOf(
    null, "", " ", "Bearer <redacted>", "bearer secret", "BEARER secret",
    "Bearer  secret", "Bearer ", "Bearer", "Basic abc", "Bearer\tx",
    "  Bearer secret  ", "bearer\u00a0secret", "bearer secret ",
    "bearer", "Bearersecret", "bearer secret\tx",
)

internal fun randomUpdateCase178(rnd: Random): UpdateCase178 {
    fun randomText(pool: List<String?>): String? {
        if (rnd.nextInt(4) == 0) return null
        if (rnd.nextInt(2) == 0) return pool[rnd.nextInt(pool.size)]
        val alphabet = "abcXYZ019 %+/._-\t\n\u00a0\u0085\u00e9\u4e2d"
        val len = rnd.nextInt(0, 40)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    fun randomToken(): String {
        if (rnd.nextInt(2) == 0) {
            return UPDATE_TOKEN_POOL178[rnd.nextInt(UPDATE_TOKEN_POOL178.size)]
        }
        val alphabet = "abcXYZ019-_ \u00a0\u00e9"
        val len = rnd.nextInt(0, 30)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    val headerLen = rnd.nextInt(0, 8)
    val header = ArrayList<Int>()
    repeat(headerLen) { header.add(rnd.nextInt(256)) }
    if (rnd.nextInt(2) == 0 && header.size >= 4) {
        header[0] = 0x50
        header[1] = 0x4B
    }
    return UpdateCase178(
        tokenExpected = randomToken(),
        tokenProvided = randomToken(),
        versionCodeRaw = randomText(UPDATE_VERSION_CODE_POOL178),
        newVersion = if (rnd.nextInt(2) == 0) rnd.nextInt(-5, 30) else rnd.nextInt(),
        currentVersion = if (rnd.nextInt(2) == 0) rnd.nextInt(-5, 30) else rnd.nextInt(),
        versionNameRaw = randomText(UPDATE_VERSION_NAME_POOL178),
        notesRaw = randomText(UPDATE_NOTES_POOL178),
        header = if (rnd.nextInt(2) == 0) {
            UPDATE_HEADER_POOL178[rnd.nextInt(UPDATE_HEADER_POOL178.size)]
        } else {
            header
        },
        baseUrl = if (rnd.nextInt(2) == 0) {
            UPDATE_URL_POOL178[rnd.nextInt(UPDATE_URL_POOL178.size)]
        } else {
            "https://h.example/" + "a".repeat(rnd.nextInt(0, 10))
        },
        authorization = randomText(UPDATE_AUTH_POOL178),
    )
}

internal fun escJsonUpdate178(s: String): String {
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

internal fun escJsonOrNullUpdate178(s: String?): String =
    if (s == null) "null" else escJsonUpdate178(s)

internal fun escJsonOrNullIntUpdate178(v: Int?): String =
    if (v == null) "null" else v.toString()

internal fun escJsonIntListUpdate178(items: List<Int>): String =
    "[" + items.joinToString(",") + "]"

internal fun updateWireJson178(c: UpdateCase178): String =
    "{\"tokenExpected\":${escJsonUpdate178(c.tokenExpected)}," +
        "\"tokenProvided\":${escJsonUpdate178(c.tokenProvided)}," +
        "\"versionCodeRaw\":${escJsonOrNullUpdate178(c.versionCodeRaw)}," +
        "\"newVersion\":${c.newVersion}," +
        "\"currentVersion\":${c.currentVersion}," +
        "\"versionNameRaw\":${escJsonOrNullUpdate178(c.versionNameRaw)}," +
        "\"notesRaw\":${escJsonOrNullUpdate178(c.notesRaw)}," +
        "\"header\":${escJsonIntListUpdate178(c.header)}," +
        "\"baseUrl\":${escJsonUpdate178(c.baseUrl)}," +
        "\"authorization\":${escJsonOrNullUpdate178(c.authorization)}}"

internal fun updateProjectionJson178(
    configured: Boolean,
    match: Boolean,
    versionCode: Int?,
    downgrade: Boolean,
    versionName: String?,
    notes: String,
    zipMagic: Boolean,
    apkUrl: String,
    bearer: String?,
): String =
    "{\"configured\":$configured," +
        "\"match\":$match," +
        "\"versionCode\":${escJsonOrNullIntUpdate178(versionCode)}," +
        "\"downgrade\":$downgrade," +
        "\"versionName\":${escJsonOrNullUpdate178(versionName)}," +
        "\"notes\":${escJsonUpdate178(notes)}," +
        "\"zipMagic\":$zipMagic," +
        "\"apkUrl\":${escJsonUpdate178(apkUrl)}," +
        "\"bearer\":${escJsonOrNullUpdate178(bearer)}}"

class AppUpdatePublishPolicyFuzzTest {

    private fun checkUpdate178(c: UpdateCase178) {
        val headerBytes = ByteArray(c.header.size) { c.header[it].toByte() }
        assertEquals(
            updateProjectionJson178(
                tokenConfiguredOracle178(c.tokenExpected),
                tokensMatchOracle178(c.tokenExpected, c.tokenProvided),
                parseVersionCodeOracle178(c.versionCodeRaw),
                isDowngradeOracle178(c.newVersion, c.currentVersion),
                parseVersionNameOracle178(c.versionNameRaw),
                sanitizeNotesOracle178(c.notesRaw),
                isZipMagicOracle178(headerBytes),
                publicApkUrlOracle178(c.baseUrl),
                bearerTokenOracle178(c.authorization),
            ),
            updateProjectionJson178(
                tokenConfiguredProduction178(c.tokenExpected),
                tokensMatchProduction178(c.tokenExpected, c.tokenProvided),
                parseVersionCodeProduction178(c.versionCodeRaw),
                isDowngradeProduction178(c.newVersion, c.currentVersion),
                parseVersionNameProduction178(c.versionNameRaw),
                sanitizeNotesProduction178(c.notesRaw),
                isZipMagicProduction178(headerBytes),
                publicApkUrlProduction178(c.baseUrl),
                bearerTokenProduction178(c.authorization),
            ),
            "update($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Tokens at the 16-char edge (incl. the lone-surrogate '?' trap);
        // version codes at the parse/range edges; notes at the % and
        // Latin-1 recovery branches; headers at the ZIP signatures.
        for (token in UPDATE_TOKEN_POOL178) {
            for (provided in listOf(token, "sixteen-chars-ok", "a?b" + "c".repeat(13), "")) {
                checkUpdate178(
                    UpdateCase178(
                        token, provided, "18", 6, 5, "1.2.3", "abc",
                        listOf(0x50, 0x4B, 0x03, 0x04), "https://x/", "Bearer secret",
                    )
                )
            }
        }
        for (raw in UPDATE_VERSION_CODE_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", raw, 6, 5, "1.2.3", "abc",
                    listOf(0x50, 0x4B, 0x03, 0x04), "https://x/", "Bearer secret",
                )
            )
        }
        for (version in listOf(-1, 0, 1, 5, 6, Int.MAX_VALUE, Int.MIN_VALUE)) {
            for (current in listOf(-1, 0, 1, 5, 6, Int.MAX_VALUE, Int.MIN_VALUE)) {
                checkUpdate178(
                    UpdateCase178(
                        "sixteen-chars-ok", "sixteen-chars-ok", "18", version, current,
                        "1.2.3", "abc", listOf(0x50, 0x4B, 0x03, 0x04),
                        "https://x/", "Bearer secret",
                    )
                )
            }
        }
        for (raw in UPDATE_VERSION_NAME_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, raw, "abc",
                    listOf(0x50, 0x4B, 0x03, 0x04), "https://x/", "Bearer secret",
                )
            )
        }
        for (notes in UPDATE_NOTES_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", notes,
                    listOf(0x50, 0x4B, 0x03, 0x04), "https://x/", "Bearer secret",
                )
            )
        }
        for (header in UPDATE_HEADER_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", "abc",
                    header, "https://x/", "Bearer secret",
                )
            )
        }
        for (url in UPDATE_URL_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", "abc",
                    listOf(0x50, 0x4B, 0x03, 0x04), url, "Bearer secret",
                )
            )
        }
        for (auth in UPDATE_AUTH_POOL178) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", "abc",
                    listOf(0x50, 0x4B, 0x03, 0x04), "https://x/", auth,
                )
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(178002)
        repeat(4000) { checkUpdate178(randomUpdateCase178(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Version codes -10..300; ZIP third/fourth byte grid at the PK
        // prefix; notes lengths 0..2100 across the 2000 cap.
        for (code in -10..300) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", code.toString(), 6, 5,
                    "1.2.3", "abc", listOf(0x50, 0x4B, 0x03, 0x04),
                    "https://x/", "Bearer secret",
                )
            )
        }
        for (third in 0..255) {
            for (fourth in listOf(0, 4, 6, 8, 255)) {
                checkUpdate178(
                    UpdateCase178(
                        "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", "abc",
                        listOf(0x50, 0x4B, third, fourth), "https://x/", "Bearer secret",
                    )
                )
            }
        }
        for (first in listOf(0x00, 0x50, 0x51, 0xFF)) {
            for (second in listOf(0x00, 0x4B, 0x4C, 0xFF)) {
                checkUpdate178(
                    UpdateCase178(
                        "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3", "abc",
                        listOf(first, second, 0x03, 0x04), "https://x/", "Bearer secret",
                    )
                )
            }
        }
        for (len in 0..2100 step 7) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3",
                    "x".repeat(len), listOf(0x50, 0x4B, 0x03, 0x04),
                    "https://x/", "Bearer secret",
                )
            )
        }
        for (len in listOf(1998, 1999, 2000, 2001, 2002)) {
            checkUpdate178(
                UpdateCase178(
                    "sixteen-chars-ok", "sixteen-chars-ok", "18", 6, 5, "1.2.3",
                    "x".repeat(len), listOf(0x50, 0x4B, 0x03, 0x04),
                    "https://x/", "Bearer secret",
                )
            )
        }
    }
}
