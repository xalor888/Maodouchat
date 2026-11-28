// StickerManifestPolicy.parseManifest + findPackContaining (144th block).
// Oracle reimplements the manifest walk from production text: JSON
// optString/optJSONArray/optJSONObject, sanitizePackId/sanitizeFileName,
// 64-hex SHA check, 64-pack / 300-sticker caps, sha lowercase. URL
// resolution is OkHttp HttpUrl, which no hand model can match, so the
// oracle looks it up in RESOLVE_TABLE144 -- JVM truth probed once over
// the whole (rawUrl, baseUrl) pool and embedded verbatim below.
package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// Java Character.isWhitespace set (28 units); Kotlin trim()/isBlank()
// use exactly this, not the <= 0x20 rule.
private val WS144 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun trim144(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i] in WS144) i++
    while (j > i && s[j - 1] in WS144) j--
    return s.substring(i, j)
}

private val POOL_URLS144 = arrayOf(
    "s.webp",
    "a/b.webp",
    "/s/a.webp",
    "./s.webp",
    "../s.webp",
    "a/../b.webp",
    "a/./b.webp",
    "s.webp?x=1",
    "s.webp#frag",
    "s.webp?x=1&y=2",
    "a b.webp",
    "a%20b.webp",
    "a+b.webp",
    "a%2Fb.webp",
    "%2e%2e/s.webp",
    "a%3Fb.webp",
    "ü.webp",
    "s.webp/",
    ".",
    "..",
    "a//b.webp",
    "s.WEBP",
    "s.webp:8080",
    "s.webp@evil.com",
    "a_b-c.webp",
    "s.webp?x=ü",
    "s.webp?x=%",
    "%zz.webp",
    "s.webp%00",
    "//example.com/s.webp",
    "//other.com/s.webp",
    "https://example.com/s.webp",
    "https://example.com:443/s.webp",
    "https://EXAMPLE.COM/s.webp",
    "https://example.com./s.webp",
    "http://example.com/s.webp",
    "https://example.com:8443/s.webp",
    "https://other.com/s.webp",
    "https://user@example.com/s.webp",
    "https://user:pass@example.com/s.webp",
    "https://example.com/s.webp#frag",
    "https://example.com",
    "ftp://example.com/s.webp",
    "javascript:alert(1)",
    "https://example.com:bad/s.webp",
    "https://[::1]/s.webp",
    "https://127.0.0.1/s.webp",
    "HTTPS://example.com/s.webp",
    "https://example.com/s.webp ",
    " https://example.com/s.webp",
    "",
    "   ",
    "https://example.com/s.webp?x=1#frag",
    "https://example.com/%2e%2e/s.webp"
)

private val POOL_BASES144 = arrayOf(
    "https://example.com",
    "https://example.com/base/",
    "http://example.com:8080",
    "not a url",
    "ftp://example.com/"
)

private val RESOLVE_TABLE144 = """
s.webp|https://example.com|https://example.com/s.webp
s.webp|https://example.com/base/|https://example.com/base/s.webp
s.webp|http://example.com:8080|http://example.com:8080/s.webp
s.webp|not a url|NULL
s.webp|ftp://example.com/|NULL
a/b.webp|https://example.com|https://example.com/a/b.webp
a/b.webp|https://example.com/base/|https://example.com/base/a/b.webp
a/b.webp|http://example.com:8080|http://example.com:8080/a/b.webp
a/b.webp|not a url|NULL
a/b.webp|ftp://example.com/|NULL
/s/a.webp|https://example.com|https://example.com/s/a.webp
/s/a.webp|https://example.com/base/|https://example.com/s/a.webp
/s/a.webp|http://example.com:8080|http://example.com:8080/s/a.webp
/s/a.webp|not a url|NULL
/s/a.webp|ftp://example.com/|NULL
./s.webp|https://example.com|https://example.com/s.webp
./s.webp|https://example.com/base/|https://example.com/base/s.webp
./s.webp|http://example.com:8080|http://example.com:8080/s.webp
./s.webp|not a url|NULL
./s.webp|ftp://example.com/|NULL
../s.webp|https://example.com|https://example.com/s.webp
../s.webp|https://example.com/base/|https://example.com/s.webp
../s.webp|http://example.com:8080|http://example.com:8080/s.webp
../s.webp|not a url|NULL
../s.webp|ftp://example.com/|NULL
a/../b.webp|https://example.com|https://example.com/b.webp
a/../b.webp|https://example.com/base/|https://example.com/base/b.webp
a/../b.webp|http://example.com:8080|http://example.com:8080/b.webp
a/../b.webp|not a url|NULL
a/../b.webp|ftp://example.com/|NULL
a/./b.webp|https://example.com|https://example.com/a/b.webp
a/./b.webp|https://example.com/base/|https://example.com/base/a/b.webp
a/./b.webp|http://example.com:8080|http://example.com:8080/a/b.webp
a/./b.webp|not a url|NULL
a/./b.webp|ftp://example.com/|NULL
s.webp?x=1|https://example.com|https://example.com/s.webp?x=1
s.webp?x=1|https://example.com/base/|https://example.com/base/s.webp?x=1
s.webp?x=1|http://example.com:8080|http://example.com:8080/s.webp?x=1
s.webp?x=1|not a url|NULL
s.webp?x=1|ftp://example.com/|NULL
s.webp#frag|https://example.com|NULL
s.webp#frag|https://example.com/base/|NULL
s.webp#frag|http://example.com:8080|NULL
s.webp#frag|not a url|NULL
s.webp#frag|ftp://example.com/|NULL
s.webp?x=1&y=2|https://example.com|https://example.com/s.webp?x=1&y=2
s.webp?x=1&y=2|https://example.com/base/|https://example.com/base/s.webp?x=1&y=2
s.webp?x=1&y=2|http://example.com:8080|http://example.com:8080/s.webp?x=1&y=2
s.webp?x=1&y=2|not a url|NULL
s.webp?x=1&y=2|ftp://example.com/|NULL
a b.webp|https://example.com|https://example.com/a%20b.webp
a b.webp|https://example.com/base/|https://example.com/base/a%20b.webp
a b.webp|http://example.com:8080|http://example.com:8080/a%20b.webp
a b.webp|not a url|NULL
a b.webp|ftp://example.com/|NULL
a%20b.webp|https://example.com|https://example.com/a%20b.webp
a%20b.webp|https://example.com/base/|https://example.com/base/a%20b.webp
a%20b.webp|http://example.com:8080|http://example.com:8080/a%20b.webp
a%20b.webp|not a url|NULL
a%20b.webp|ftp://example.com/|NULL
a+b.webp|https://example.com|https://example.com/a+b.webp
a+b.webp|https://example.com/base/|https://example.com/base/a+b.webp
a+b.webp|http://example.com:8080|http://example.com:8080/a+b.webp
a+b.webp|not a url|NULL
a+b.webp|ftp://example.com/|NULL
a%2Fb.webp|https://example.com|https://example.com/a%2Fb.webp
a%2Fb.webp|https://example.com/base/|https://example.com/base/a%2Fb.webp
a%2Fb.webp|http://example.com:8080|http://example.com:8080/a%2Fb.webp
a%2Fb.webp|not a url|NULL
a%2Fb.webp|ftp://example.com/|NULL
%2e%2e/s.webp|https://example.com|https://example.com/s.webp
%2e%2e/s.webp|https://example.com/base/|https://example.com/s.webp
%2e%2e/s.webp|http://example.com:8080|http://example.com:8080/s.webp
%2e%2e/s.webp|not a url|NULL
%2e%2e/s.webp|ftp://example.com/|NULL
a%3Fb.webp|https://example.com|https://example.com/a%3Fb.webp
a%3Fb.webp|https://example.com/base/|https://example.com/base/a%3Fb.webp
a%3Fb.webp|http://example.com:8080|http://example.com:8080/a%3Fb.webp
a%3Fb.webp|not a url|NULL
a%3Fb.webp|ftp://example.com/|NULL
ü.webp|https://example.com|https://example.com/%C3%BC.webp
ü.webp|https://example.com/base/|https://example.com/base/%C3%BC.webp
ü.webp|http://example.com:8080|http://example.com:8080/%C3%BC.webp
ü.webp|not a url|NULL
ü.webp|ftp://example.com/|NULL
s.webp/|https://example.com|https://example.com/s.webp/
s.webp/|https://example.com/base/|https://example.com/base/s.webp/
s.webp/|http://example.com:8080|http://example.com:8080/s.webp/
s.webp/|not a url|NULL
s.webp/|ftp://example.com/|NULL
.|https://example.com|https://example.com/
.|https://example.com/base/|https://example.com/base/
.|http://example.com:8080|http://example.com:8080/
.|not a url|NULL
.|ftp://example.com/|NULL
..|https://example.com|https://example.com/
..|https://example.com/base/|https://example.com/
..|http://example.com:8080|http://example.com:8080/
..|not a url|NULL
..|ftp://example.com/|NULL
a//b.webp|https://example.com|https://example.com/a//b.webp
a//b.webp|https://example.com/base/|https://example.com/base/a//b.webp
a//b.webp|http://example.com:8080|http://example.com:8080/a//b.webp
a//b.webp|not a url|NULL
a//b.webp|ftp://example.com/|NULL
s.WEBP|https://example.com|https://example.com/s.WEBP
s.WEBP|https://example.com/base/|https://example.com/base/s.WEBP
s.WEBP|http://example.com:8080|http://example.com:8080/s.WEBP
s.WEBP|not a url|NULL
s.WEBP|ftp://example.com/|NULL
s.webp:8080|https://example.com|NULL
s.webp:8080|https://example.com/base/|NULL
s.webp:8080|http://example.com:8080|NULL
s.webp:8080|not a url|NULL
s.webp:8080|ftp://example.com/|NULL
s.webp@evil.com|https://example.com|https://example.com/s.webp@evil.com
s.webp@evil.com|https://example.com/base/|https://example.com/base/s.webp@evil.com
s.webp@evil.com|http://example.com:8080|http://example.com:8080/s.webp@evil.com
s.webp@evil.com|not a url|NULL
s.webp@evil.com|ftp://example.com/|NULL
a_b-c.webp|https://example.com|https://example.com/a_b-c.webp
a_b-c.webp|https://example.com/base/|https://example.com/base/a_b-c.webp
a_b-c.webp|http://example.com:8080|http://example.com:8080/a_b-c.webp
a_b-c.webp|not a url|NULL
a_b-c.webp|ftp://example.com/|NULL
s.webp?x=ü|https://example.com|https://example.com/s.webp?x=%C3%BC
s.webp?x=ü|https://example.com/base/|https://example.com/base/s.webp?x=%C3%BC
s.webp?x=ü|http://example.com:8080|http://example.com:8080/s.webp?x=%C3%BC
s.webp?x=ü|not a url|NULL
s.webp?x=ü|ftp://example.com/|NULL
s.webp?x=%|https://example.com|https://example.com/s.webp?x=%
s.webp?x=%|https://example.com/base/|https://example.com/base/s.webp?x=%
s.webp?x=%|http://example.com:8080|http://example.com:8080/s.webp?x=%
s.webp?x=%|not a url|NULL
s.webp?x=%|ftp://example.com/|NULL
%zz.webp|https://example.com|https://example.com/%zz.webp
%zz.webp|https://example.com/base/|https://example.com/base/%zz.webp
%zz.webp|http://example.com:8080|http://example.com:8080/%zz.webp
%zz.webp|not a url|NULL
%zz.webp|ftp://example.com/|NULL
s.webp%00|https://example.com|https://example.com/s.webp%00
s.webp%00|https://example.com/base/|https://example.com/base/s.webp%00
s.webp%00|http://example.com:8080|http://example.com:8080/s.webp%00
s.webp%00|not a url|NULL
s.webp%00|ftp://example.com/|NULL
//example.com/s.webp|https://example.com|https://example.com/s.webp
//example.com/s.webp|https://example.com/base/|https://example.com/s.webp
//example.com/s.webp|http://example.com:8080|NULL
//example.com/s.webp|not a url|NULL
//example.com/s.webp|ftp://example.com/|NULL
//other.com/s.webp|https://example.com|NULL
//other.com/s.webp|https://example.com/base/|NULL
//other.com/s.webp|http://example.com:8080|NULL
//other.com/s.webp|not a url|NULL
//other.com/s.webp|ftp://example.com/|NULL
https://example.com/s.webp|https://example.com|https://example.com/s.webp
https://example.com/s.webp|https://example.com/base/|https://example.com/s.webp
https://example.com/s.webp|http://example.com:8080|NULL
https://example.com/s.webp|not a url|NULL
https://example.com/s.webp|ftp://example.com/|NULL
https://example.com:443/s.webp|https://example.com|https://example.com/s.webp
https://example.com:443/s.webp|https://example.com/base/|https://example.com/s.webp
https://example.com:443/s.webp|http://example.com:8080|NULL
https://example.com:443/s.webp|not a url|NULL
https://example.com:443/s.webp|ftp://example.com/|NULL
https://EXAMPLE.COM/s.webp|https://example.com|https://example.com/s.webp
https://EXAMPLE.COM/s.webp|https://example.com/base/|https://example.com/s.webp
https://EXAMPLE.COM/s.webp|http://example.com:8080|NULL
https://EXAMPLE.COM/s.webp|not a url|NULL
https://EXAMPLE.COM/s.webp|ftp://example.com/|NULL
https://example.com./s.webp|https://example.com|NULL
https://example.com./s.webp|https://example.com/base/|NULL
https://example.com./s.webp|http://example.com:8080|NULL
https://example.com./s.webp|not a url|NULL
https://example.com./s.webp|ftp://example.com/|NULL
http://example.com/s.webp|https://example.com|NULL
http://example.com/s.webp|https://example.com/base/|NULL
http://example.com/s.webp|http://example.com:8080|NULL
http://example.com/s.webp|not a url|NULL
http://example.com/s.webp|ftp://example.com/|NULL
https://example.com:8443/s.webp|https://example.com|NULL
https://example.com:8443/s.webp|https://example.com/base/|NULL
https://example.com:8443/s.webp|http://example.com:8080|NULL
https://example.com:8443/s.webp|not a url|NULL
https://example.com:8443/s.webp|ftp://example.com/|NULL
https://other.com/s.webp|https://example.com|NULL
https://other.com/s.webp|https://example.com/base/|NULL
https://other.com/s.webp|http://example.com:8080|NULL
https://other.com/s.webp|not a url|NULL
https://other.com/s.webp|ftp://example.com/|NULL
https://user@example.com/s.webp|https://example.com|NULL
https://user@example.com/s.webp|https://example.com/base/|NULL
https://user@example.com/s.webp|http://example.com:8080|NULL
https://user@example.com/s.webp|not a url|NULL
https://user@example.com/s.webp|ftp://example.com/|NULL
https://user:pass@example.com/s.webp|https://example.com|NULL
https://user:pass@example.com/s.webp|https://example.com/base/|NULL
https://user:pass@example.com/s.webp|http://example.com:8080|NULL
https://user:pass@example.com/s.webp|not a url|NULL
https://user:pass@example.com/s.webp|ftp://example.com/|NULL
https://example.com/s.webp#frag|https://example.com|NULL
https://example.com/s.webp#frag|https://example.com/base/|NULL
https://example.com/s.webp#frag|http://example.com:8080|NULL
https://example.com/s.webp#frag|not a url|NULL
https://example.com/s.webp#frag|ftp://example.com/|NULL
https://example.com|https://example.com|https://example.com/
https://example.com|https://example.com/base/|https://example.com/
https://example.com|http://example.com:8080|NULL
https://example.com|not a url|NULL
https://example.com|ftp://example.com/|NULL
ftp://example.com/s.webp|https://example.com|NULL
ftp://example.com/s.webp|https://example.com/base/|NULL
ftp://example.com/s.webp|http://example.com:8080|NULL
ftp://example.com/s.webp|not a url|NULL
ftp://example.com/s.webp|ftp://example.com/|NULL
javascript:alert(1)|https://example.com|NULL
javascript:alert(1)|https://example.com/base/|NULL
javascript:alert(1)|http://example.com:8080|NULL
javascript:alert(1)|not a url|NULL
javascript:alert(1)|ftp://example.com/|NULL
https://example.com:bad/s.webp|https://example.com|NULL
https://example.com:bad/s.webp|https://example.com/base/|NULL
https://example.com:bad/s.webp|http://example.com:8080|NULL
https://example.com:bad/s.webp|not a url|NULL
https://example.com:bad/s.webp|ftp://example.com/|NULL
https://[::1]/s.webp|https://example.com|NULL
https://[::1]/s.webp|https://example.com/base/|NULL
https://[::1]/s.webp|http://example.com:8080|NULL
https://[::1]/s.webp|not a url|NULL
https://[::1]/s.webp|ftp://example.com/|NULL
https://127.0.0.1/s.webp|https://example.com|NULL
https://127.0.0.1/s.webp|https://example.com/base/|NULL
https://127.0.0.1/s.webp|http://example.com:8080|NULL
https://127.0.0.1/s.webp|not a url|NULL
https://127.0.0.1/s.webp|ftp://example.com/|NULL
HTTPS://example.com/s.webp|https://example.com|https://example.com/s.webp
HTTPS://example.com/s.webp|https://example.com/base/|https://example.com/s.webp
HTTPS://example.com/s.webp|http://example.com:8080|NULL
HTTPS://example.com/s.webp|not a url|NULL
HTTPS://example.com/s.webp|ftp://example.com/|NULL
https://example.com/s.webp |https://example.com|https://example.com/s.webp
https://example.com/s.webp |https://example.com/base/|https://example.com/s.webp
https://example.com/s.webp |http://example.com:8080|NULL
https://example.com/s.webp |not a url|NULL
https://example.com/s.webp |ftp://example.com/|NULL
 https://example.com/s.webp|https://example.com|https://example.com/s.webp
 https://example.com/s.webp|https://example.com/base/|https://example.com/s.webp
 https://example.com/s.webp|http://example.com:8080|NULL
 https://example.com/s.webp|not a url|NULL
 https://example.com/s.webp|ftp://example.com/|NULL
|https://example.com|NULL
|https://example.com/base/|NULL
|http://example.com:8080|NULL
|not a url|NULL
|ftp://example.com/|NULL
   |https://example.com|NULL
   |https://example.com/base/|NULL
   |http://example.com:8080|NULL
   |not a url|NULL
   |ftp://example.com/|NULL
https://example.com/s.webp?x=1#frag|https://example.com|NULL
https://example.com/s.webp?x=1#frag|https://example.com/base/|NULL
https://example.com/s.webp?x=1#frag|http://example.com:8080|NULL
https://example.com/s.webp?x=1#frag|not a url|NULL
https://example.com/s.webp?x=1#frag|ftp://example.com/|NULL
https://example.com/%2e%2e/s.webp|https://example.com|https://example.com/s.webp
https://example.com/%2e%2e/s.webp|https://example.com/base/|https://example.com/s.webp
https://example.com/%2e%2e/s.webp|http://example.com:8080|NULL
https://example.com/%2e%2e/s.webp|not a url|NULL
https://example.com/%2e%2e/s.webp|ftp://example.com/|NULL
"""

private val RESOLVE_MAP144: Map<String, String?> = buildMap {
    for (line in RESOLVE_TABLE144.trim().split("\n")) {
        val parts = line.split("|")
        put(parts[0] + "\u0001" + parts[1], if (parts[2] == "NULL") null else parts[2])
    }
}

private fun resolveOracle144(raw: String, base: String): String? {
    val key = raw + "\u0001" + base
    // Map.get returns null both for a missing key and for a null-valued
    // entry (171 of 270 resolve to null), so check membership first.
    if (key !in RESOLVE_MAP144) error("resolve table miss: raw=[$raw] base=[$base]")
    return RESOLVE_MAP144.getValue(key)
}

private fun sanitizePackIdOracle144(id: String): String {
    val cleaned = trim144(id).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    val taken = if (cleaned.length > 40) cleaned.substring(0, 40) else cleaned
    return if (taken == "." || taken == "..") "" else taken
}

private fun sanitizeFileNameOracle144(name: String): String {
    val cleaned = trim144(name).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    val taken = if (cleaned.length > 80) cleaned.substring(0, 80) else cleaned
    return if (taken == "." || taken == "..") "" else taken
}

private fun optStringOracle144(obj: JSONObject, key: String): String {
    val v = obj.opt(key) ?: return ""
    if (v === JSONObject.NULL) return ""
    if (v is String) return v
    return v.toString()
}

private fun isShaOracle144(s: String): Boolean {
    if (s.length != 64) return false
    return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private fun lowerHexOracle144(s: String): String =
    s.map { if (it in 'A'..'F') it + 32 else it }.joinToString("")

private fun parseManifestOracle144(raw: String, baseUrl: String): List<RemotePack> {
    val root = JSONObject(raw)
    val packs = root.optJSONArray("packs") ?: JSONArray()
    val out = mutableListOf<RemotePack>()
    var packCount = 0
    for (i in 0 until packs.length()) {
        if (packCount >= 64) break
        val pack = packs.optJSONObject(i) ?: continue
        val id = trim144(optStringOracle144(pack, "id"))
        if (id.isEmpty() || sanitizePackIdOracle144(id) != id) continue
        val stickers = pack.optJSONArray("stickers") ?: JSONArray()
        val items = mutableListOf<RemoteSticker>()
        var stickerCount = 0
        for (j in 0 until stickers.length()) {
            if (stickerCount >= 300) break
            val s = stickers.optJSONObject(j) ?: continue
            val name = trim144(optStringOracle144(s, "name"))
            if (name.isEmpty() || sanitizeFileNameOracle144(name) != name) continue
            val rawUrl = trim144(optStringOracle144(s, "url"))
            if (name.isEmpty() || rawUrl.isEmpty()) continue
            val url = resolveOracle144(rawUrl, baseUrl) ?: continue
            val rawSha = trim144(optStringOracle144(s, "sha256"))
            if (!isShaOracle144(rawSha)) continue
            items += RemoteSticker(name, url, lowerHexOracle144(rawSha))
            stickerCount++
        }
        if (items.isNotEmpty()) {
            out += RemotePack(id, items)
            packCount++
        }
    }
    return out
}

private fun findPackContainingOracle144(packs: List<RemotePack>, fileName: String): RemotePack? =
    packs.firstOrNull { pack -> pack.stickers.any { sanitizeFileNameOracle144(it.name) == fileName } }

// ---------- generators (mirrored by Oracle144 GEN, seed 144003) ----------

private val PACK_ID_POOL144 = arrayOf(
    "cute", "a", "ok-pack_1", "bad id", "bad/id", "a".repeat(39),
    "a".repeat(40), "a".repeat(41), ".", "..", "...", "", "  cute  ",
    "UPPER", "a.b", "a b c", "é", "a|b", "a_b-c9",
)
private val STICKER_NAME_POOL144 = arrayOf(
    "cat01.webp", "a.webp", "bad/name.webp", "a".repeat(79) + ".webp",
    "a".repeat(80), "a".repeat(81), ".", "..", "", "  ok.webp  ",
    "UP.WEBP", "a b.webp", "é.webp", "x", "a..webp", ".webp",
)
private val SHA_POOL144 = arrayOf(
    "a".repeat(64), "A".repeat(64), "0123456789abcdef".repeat(4),
    "a".repeat(63), "a".repeat(65), "xyz", "g".repeat(64), "",
    "a".repeat(32) + "Z".repeat(32), "Ab".repeat(32),
)

private fun randomWord144(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 10)
    repeat(len) {
        when (rnd.nextInt(4)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            else -> sb.append(WS144.elementAt(rnd.nextInt(WS144.size)))
        }
    }
    return sb.toString()
}

private fun putScalar144(o: JSONObject, key: String, v: Any?) {
    when (v) {
        null -> o.put(key, JSONObject.NULL)
        is List<*> -> o.put(key, JSONArray(v))
        is Map<*, *> -> o.put(key, JSONObject(v))
        else -> o.put(key, v)
    }
}

private fun randomPackId144(rnd: Random): Any? = when (rnd.nextInt(6)) {
    0 -> PACK_ID_POOL144[rnd.nextInt(PACK_ID_POOL144.size)]
    1 -> randomWord144(rnd)
    2 -> rnd.nextInt(-100, 100)
    3 -> rnd.nextBoolean()
    4 -> null
    else -> PACK_ID_POOL144[rnd.nextInt(PACK_ID_POOL144.size)]
}

private fun randomStickerName144(rnd: Random): Any? = when (rnd.nextInt(6)) {
    0 -> STICKER_NAME_POOL144[rnd.nextInt(STICKER_NAME_POOL144.size)]
    1 -> randomWord144(rnd)
    2 -> rnd.nextInt(-100, 100)
    3 -> rnd.nextBoolean()
    4 -> null
    else -> STICKER_NAME_POOL144[rnd.nextInt(STICKER_NAME_POOL144.size)]
}

private fun randomSha144(rnd: Random): Any? = when (rnd.nextInt(6)) {
    0 -> SHA_POOL144[rnd.nextInt(SHA_POOL144.size)]
    1 -> randomWord144(rnd)
    2 -> rnd.nextInt(-100, 100)
    3 -> rnd.nextBoolean()
    4 -> null
    else -> SHA_POOL144[rnd.nextInt(SHA_POOL144.size)]
}

private fun randomSticker144(rnd: Random): JSONObject {
    val s = JSONObject()
    putScalar144(s, "name", randomStickerName144(rnd))
    // url stays inside the resolve pool: the oracle is a probed table,
    // and only pool strings have a table entry.
    s.put("url", POOL_URLS144[rnd.nextInt(POOL_URLS144.size)])
    putScalar144(s, "sha256", randomSha144(rnd))
    if (rnd.nextInt(8) == 0) s.put("extra", "ignored")
    return s
}

private fun randomPack144(rnd: Random): JSONObject {
    val pack = JSONObject()
    putScalar144(pack, "id", randomPackId144(rnd))
    when (rnd.nextInt(6)) {
        0 -> {}
        1 -> pack.put("stickers", "not an array")
        else -> {
            val arr = JSONArray()
            val m = if (rnd.nextInt(10) == 0) rnd.nextInt(295, 310) else rnd.nextInt(0, 5)
            repeat(m) {
                when (rnd.nextInt(8)) {
                    0 -> arr.put("not an object")
                    1 -> arr.put(rnd.nextInt(100))
                    2 -> arr.put(JSONObject.NULL)
                    else -> arr.put(randomSticker144(rnd))
                }
            }
            pack.put("stickers", arr)
        }
    }
    return pack
}

private fun randomManifest144(rnd: Random): JSONObject {
    val root = JSONObject()
    when (rnd.nextInt(6)) {
        0 -> {}
        1 -> root.put("packs", "not an array")
        2 -> root.put("packs", 42)
        else -> {
            val packs = JSONArray()
            val n = if (rnd.nextInt(10) == 0) rnd.nextInt(60, 75) else rnd.nextInt(0, 5)
            repeat(n) {
                when (rnd.nextInt(8)) {
                    0 -> packs.put("not an object")
                    1 -> packs.put(rnd.nextInt(100))
                    2 -> packs.put(JSONObject.NULL)
                    else -> packs.put(randomPack144(rnd))
                }
            }
            root.put("packs", packs)
        }
    }
    if (rnd.nextInt(5) == 0) root.put("extra", "ignored")
    return root
}

private fun randomPacks144(rnd: Random): List<RemotePack> {
    val n = rnd.nextInt(0, 4)
    return List(n) {
        val id = PACK_ID_POOL144[rnd.nextInt(PACK_ID_POOL144.size)]
        val m = rnd.nextInt(0, 4)
        val stickers = List(m) {
            RemoteSticker(
                STICKER_NAME_POOL144[rnd.nextInt(STICKER_NAME_POOL144.size)],
                POOL_URLS144[rnd.nextInt(POOL_URLS144.size)],
                SHA_POOL144[rnd.nextInt(SHA_POOL144.size)],
            )
        }
        RemotePack(id, stickers)
    }
}

class StickerManifestPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val sha = "a".repeat(64)
        val base = "https://example.com"
        fun manifest(vararg packs: JSONObject): String =
            JSONObject().put("packs", JSONArray(packs.toList())).toString()
        fun pack(id: String, vararg stickers: JSONObject): JSONObject {
            val obj = JSONObject().put("id", id)
            if (stickers.isNotEmpty()) obj.put("stickers", JSONArray(stickers.toList()))
            return obj
        }
        fun sticker(name: String, url: String, sha256: String): JSONObject =
            JSONObject().put("name", name).put("url", url).put("sha256", sha256)
        val raws = listOf(
            manifest(pack("cute", sticker("cat01.webp", "/s/a.webp", "A".repeat(64)))) to base,
            manifest(
                pack("bad id", sticker("cat01.webp", "/s/a.webp", sha)),
                pack("empty"),
                pack("ok", sticker("cat01.webp", "/s/a.webp", sha)),
            ) to base,
            manifest(
                pack(
                    "cute",
                    sticker("bad/name.webp", "/s/a.webp", sha),
                    sticker("ok.webp", "", sha),
                    sticker("badsha.webp", "/s/a.webp", "xyz"),
                    sticker("good.webp", "/s/a.webp", sha),
                ),
            ) to base,
            manifest() to base,
            JSONObject().put("packs", "nope").toString() to base,
            JSONObject().toString() to base,
            manifest(pack("cute", sticker("cat01.webp", "https://other.com/s.webp", sha))) to base,
            manifest(pack("cute", sticker("cat01.webp", "/s/a.webp", sha))) to "http://example.com:8080",
            manifest(pack("cute", sticker("cat01.webp", "/s/a.webp", sha))) to "not a url",
            manifest(pack("cute", sticker("cat01.webp", "s.webp#frag", sha))) to base,
            manifest(pack(".", sticker("cat01.webp", "/s/a.webp", sha))) to base,
            manifest(pack("..", sticker("cat01.webp", "/s/a.webp", sha))) to base,
            manifest(pack("a".repeat(41), sticker("cat01.webp", "/s/a.webp", sha))) to base,
        )
        for ((raw, b) in raws) {
            assertEquals(
                parseManifestOracle144(raw, b),
                StickerManifestPolicy.parseManifest(raw, b),
            )
        }
        val packs = (0 until 70).map { pack("p$it", sticker("a.webp", "/s/a.webp", sha)) }.toTypedArray()
        val raw70 = manifest(*packs)
        assertEquals(parseManifestOracle144(raw70, base), StickerManifestPolicy.parseManifest(raw70, base))
        val stickers = (0 until 310).map { sticker("s$it.webp", "/s/a.webp", sha) }.toTypedArray()
        val raw310 = manifest(pack("cute", *stickers))
        assertEquals(parseManifestOracle144(raw310, base), StickerManifestPolicy.parseManifest(raw310, base))
        for (bad in listOf("", "{", "not json", "{\"packs\":}")) {
            assertFailsWith<JSONException> { StickerManifestPolicy.parseManifest(bad, base) }
            assertFailsWith<JSONException> { parseManifestOracle144(bad, base) }
        }
        val found = StickerManifestPolicy.parseManifest(
            manifest(pack("cute", sticker("cat01.webp", "/s/a.webp", sha))), base,
        )
        assertEquals("cute", StickerManifestPolicy.findPackContaining(found, "cat01.webp")?.id)
        assertEquals(
            findPackContainingOracle144(found, "cat01.webp")?.id,
            StickerManifestPolicy.findPackContaining(found, "cat01.webp")?.id,
        )
        assertNull(StickerManifestPolicy.findPackContaining(found, "missing.webp"))
        assertNull(findPackContainingOracle144(found, "missing.webp"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(144003)
        repeat(1500) {
            val raw = randomManifest144(rnd).toString()
            val base = POOL_BASES144[rnd.nextInt(POOL_BASES144.size)]
            assertEquals(
                parseManifestOracle144(raw, base),
                StickerManifestPolicy.parseManifest(raw, base),
            )
            val packs = randomPacks144(rnd)
            val fileName = STICKER_NAME_POOL144[rnd.nextInt(STICKER_NAME_POOL144.size)]
            assertEquals(
                findPackContainingOracle144(packs, fileName)?.id,
                StickerManifestPolicy.findPackContaining(packs, fileName)?.id,
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val sha = "a".repeat(64)
        val base = "https://example.com"
        // One field at a time across its pool, everything else fixed valid.
        for (id in PACK_ID_POOL144) {
            val raw = JSONObject().put("packs", JSONArray(listOf(
                JSONObject().put("id", id).put("stickers", JSONArray(listOf(
                    JSONObject().put("name", "a.webp").put("url", "/s/a.webp").put("sha256", sha),
                ))),
            ))).toString()
            assertEquals(parseManifestOracle144(raw, base), StickerManifestPolicy.parseManifest(raw, base))
        }
        for (name in STICKER_NAME_POOL144) {
            val raw = JSONObject().put("packs", JSONArray(listOf(
                JSONObject().put("id", "cute").put("stickers", JSONArray(listOf(
                    JSONObject().put("name", name).put("url", "/s/a.webp").put("sha256", sha),
                ))),
            ))).toString()
            assertEquals(parseManifestOracle144(raw, base), StickerManifestPolicy.parseManifest(raw, base))
        }
        for (url in POOL_URLS144) {
            val raw = JSONObject().put("packs", JSONArray(listOf(
                JSONObject().put("id", "cute").put("stickers", JSONArray(listOf(
                    JSONObject().put("name", "a.webp").put("url", url).put("sha256", sha),
                ))),
            ))).toString()
            assertEquals(parseManifestOracle144(raw, base), StickerManifestPolicy.parseManifest(raw, base))
        }
        for (s in SHA_POOL144) {
            val raw = JSONObject().put("packs", JSONArray(listOf(
                JSONObject().put("id", "cute").put("stickers", JSONArray(listOf(
                    JSONObject().put("name", "a.webp").put("url", "/s/a.webp").put("sha256", s),
                ))),
            ))).toString()
            assertEquals(parseManifestOracle144(raw, base), StickerManifestPolicy.parseManifest(raw, base))
        }
        for (b in POOL_BASES144) {
            val raw = JSONObject().put("packs", JSONArray(listOf(
                JSONObject().put("id", "cute").put("stickers", JSONArray(listOf(
                    JSONObject().put("name", "a.webp").put("url", "/s/a.webp").put("sha256", sha),
                ))),
            ))).toString()
            assertEquals(parseManifestOracle144(raw, b), StickerManifestPolicy.parseManifest(raw, b))
        }
        // findPackContaining sweep: one pack, one sticker, every name.
        for (name in STICKER_NAME_POOL144) {
            val packs = listOf(RemotePack("p", listOf(RemoteSticker(name, "https://example.com/s.webp", sha))))
            assertEquals(
                findPackContainingOracle144(packs, name)?.id,
                StickerManifestPolicy.findPackContaining(packs, name)?.id,
            )
            assertEquals(
                findPackContainingOracle144(packs, "zzz.webp")?.id,
                StickerManifestPolicy.findPackContaining(packs, "zzz.webp")?.id,
            )
        }
    }
}
