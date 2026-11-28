package com.maodouchat.server.repository

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// VisibilityPolicy.normalize restated by hand: trim, uppercase, keep
// only the three known values, everything else falls back to PRIVATE.
internal fun normalizeVisibilityOracle177(value: String): String {
    val normalized = value.trim().uppercase()
    return if (normalized in setOf("PUBLIC", "CONTACTS", "PRIVATE")) normalized else "PRIVATE"
}

// VisibilityPolicy.isVisible restated by hand: the author always sees
// their own post; a blocked author is invisible to the viewer;
// otherwise the normalized visibility decides.
internal fun isVisibleOracle177(
    visibility: String,
    authorId: String,
    viewerId: String,
    contactIds: Set<String>,
    blockedUserIds: Set<String>,
): Boolean {
    if (authorId == viewerId) return true
    if (authorId in blockedUserIds) return false
    return when (normalizeVisibilityOracle177(visibility)) {
        "PUBLIC" -> true
        "CONTACTS" -> authorId in contactIds
        else -> false
    }
}

// stripInlineMeta restated by hand, without the regex: scan for
// "<meta>" and drop through the first "</meta>" after it; an opening
// without a closer is dropped by the stray-tag pass below, exactly
// like the production replace chain does.
internal fun stripInlineMetaOracle177(content: String): String {
    val kept = StringBuilder()
    var i = 0
    while (i < content.length) {
        val open = content.indexOf("<meta>", i)
        if (open < 0) {
            kept.append(content.substring(i))
            break
        }
        kept.append(content.substring(i, open))
        val close = content.indexOf("</meta>", open + 6)
        if (close < 0) {
            i = open + 6
            continue
        }
        i = close + 7
    }
    return kept.toString().replace("</meta>", "").replace("<meta>", "").trim()
}

// stripInlineMetaPreservingTrailing restated by hand: same body as
// above, but the final meta block is kept when it is not already
// part of the body.
internal fun stripInlineMetaPreservingTrailingOracle177(content: String): String {
    var trailing: String? = null
    var i = 0
    while (i < content.length) {
        val open = content.indexOf("<meta>", i)
        if (open < 0) break
        val close = content.indexOf("</meta>", open + 6)
        if (close < 0) break
        trailing = content.substring(open, close + 7)
        i = close + 7
    }
    val body = stripInlineMetaOracle177(content)
    return if (trailing == null || trailing in body) body else body + trailing
}

// Production VisibilityPolicy.normalize normalizes the visibility.
internal fun normalizeVisibilityProduction177(value: String): String =
    VisibilityPolicy.normalize(value)

// Production VisibilityPolicy.isVisible gates the post for the viewer.
internal fun isVisibleProduction177(
    visibility: String,
    authorId: String,
    viewerId: String,
    contactIds: Set<String>,
    blockedUserIds: Set<String>,
): Boolean = VisibilityPolicy.isVisible(visibility, authorId, viewerId, contactIds, blockedUserIds)

// Production stripInlineMeta removes client-rendered meta blocks.
internal fun stripInlineMetaProduction177(content: String): String =
    stripInlineMeta(content)

// Production stripInlineMetaPreservingTrailing keeps the final block.
internal fun stripInlineMetaPreservingTrailingProduction177(content: String): String =
    stripInlineMetaPreservingTrailing(content)

// ---------- generators ----------

internal data class ServerVisibilityCase177(
    val visibility: String,
    val authorId: String,
    val viewerId: String,
    val contactIds: List<String>,
    val blockedUserIds: List<String>,
    val content: String,
)

internal val VISIBILITY_POOL177: List<String> = listOf(
    "PUBLIC", "CONTACTS", "PRIVATE", "public", "Public", "pUbLiC", "FRIENDS", "",
    " ", "PUBLIC ", " public", "PRIVAT", "CONTACT", "PUBLIC\u00a0", "CONTACTS ",
    "PUBLİC", "ß", "ﬀ", "ǅ", "public\n", "\tPRIVATE\t", "PUBLIC\u2007",
)

internal val VISIBILITY_ID_POOL177: List<String> = listOf(
    "", " ", "u1", "u2", "user-1", "  u1  ", "u1\u00a0", "\u00a0", "a".repeat(60),
)

internal val META_FRAGMENT_POOL177: List<String> = listOf(
    "", "hello", " ", "\n", "<meta>", "</meta>", "<meta>x</meta>",
    "<meta></meta>", "<meta><meta>a</meta>", "a<meta>b", "</meta>c",
    "<meta>a</meta><meta>", "<meta>a</meta>b</meta>", "<meta>\n</meta>",
    "<meta>a<meta>b</meta>c</meta>d", "<meta", "</meta", "<meta/>",
    "<META>x</META>", "<meta >x</meta >", "x<meta>y</meta>z<meta>w</meta>",
    "ü<meta>ß</meta>ﬀ", "<meta>a</meta", "pre<meta>mid</meta>post",
)

internal fun randomServerVisibilityCase177(rnd: Random): ServerVisibilityCase177 {
    fun randomId(): String {
        if (rnd.nextInt(3) == 0) {
            return VISIBILITY_ID_POOL177[rnd.nextInt(VISIBILITY_ID_POOL177.size)]
        }
        val alphabet = "u12ab "
        val len = rnd.nextInt(0, 6)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    fun randomContent(): String {
        if (rnd.nextInt(3) == 0) {
            return META_FRAGMENT_POOL177[rnd.nextInt(META_FRAGMENT_POOL177.size)]
        }
        val n = rnd.nextInt(0, 4)
        val sb = StringBuilder()
        repeat(n) {
            sb.append(META_FRAGMENT_POOL177[rnd.nextInt(META_FRAGMENT_POOL177.size)])
        }
        return sb.toString()
    }
    val ids = listOf("u1", "u2", "user-1")
    return ServerVisibilityCase177(
        visibility = if (rnd.nextInt(2) == 0) {
            VISIBILITY_POOL177[rnd.nextInt(VISIBILITY_POOL177.size)]
        } else {
            randomId().uppercase()
        },
        authorId = randomId(),
        viewerId = randomId(),
        contactIds = ids.filter { rnd.nextBoolean() },
        blockedUserIds = ids.filter { rnd.nextBoolean() },
        content = randomContent(),
    )
}

internal fun escJsonVisibility177(s: String): String {
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

internal fun escJsonStringListVisibility177(items: List<String>): String =
    "[" + items.joinToString(",") { escJsonVisibility177(it) } + "]"

internal fun serverVisibilityWireJson177(c: ServerVisibilityCase177): String =
    "{\"visibility\":${escJsonVisibility177(c.visibility)}," +
        "\"authorId\":${escJsonVisibility177(c.authorId)}," +
        "\"viewerId\":${escJsonVisibility177(c.viewerId)}," +
        "\"contactIds\":${escJsonStringListVisibility177(c.contactIds)}," +
        "\"blockedUserIds\":${escJsonStringListVisibility177(c.blockedUserIds)}," +
        "\"content\":${escJsonVisibility177(c.content)}}"

internal fun serverVisibilityProjectionJson177(
    normalized: String,
    visible: Boolean,
    stripped: String,
    preserving: String,
): String =
    "{\"normalized\":${escJsonVisibility177(normalized)}," +
        "\"visible\":$visible," +
        "\"stripped\":${escJsonVisibility177(stripped)}," +
        "\"preserving\":${escJsonVisibility177(preserving)}}"

class ServerVisibilityMetaFuzzTest {

    private fun checkServerVisibility177(c: ServerVisibilityCase177) {
        val contacts = c.contactIds.toSet()
        val blocked = c.blockedUserIds.toSet()
        assertEquals(
            serverVisibilityProjectionJson177(
                normalizeVisibilityOracle177(c.visibility),
                isVisibleOracle177(c.visibility, c.authorId, c.viewerId, contacts, blocked),
                stripInlineMetaOracle177(c.content),
                stripInlineMetaPreservingTrailingOracle177(c.content),
            ),
            serverVisibilityProjectionJson177(
                normalizeVisibilityProduction177(c.visibility),
                isVisibleProduction177(c.visibility, c.authorId, c.viewerId, contacts, blocked),
                stripInlineMetaProduction177(c.content),
                stripInlineMetaPreservingTrailingProduction177(c.content),
            ),
            "serverVisibility($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every visibility value against the author/viewer/blocked
        // matrix, and every meta fragment on its own.
        for (visibility in VISIBILITY_POOL177) {
            for (author in VISIBILITY_ID_POOL177) {
                for (viewer in listOf("", "u1", "u2")) {
                    checkServerVisibility177(
                        ServerVisibilityCase177(
                            visibility, author, viewer,
                            listOf("u1"), listOf("u2"), ""
                        )
                    )
                    checkServerVisibility177(
                        ServerVisibilityCase177(
                            visibility, author, viewer,
                            emptyList(), listOf(author), ""
                        )
                    )
                }
            }
        }
        for (fragment in META_FRAGMENT_POOL177) {
            checkServerVisibility177(
                ServerVisibilityCase177(
                    "PUBLIC", "u1", "u2", listOf("u1"), emptyList(), fragment
                )
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(177003)
        repeat(4000) { checkServerVisibility177(randomServerVisibilityCase177(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every one- and two-fragment meta concatenation, plus the
        // visibility value crossed with every contact/blocked subset.
        for (a in META_FRAGMENT_POOL177) {
            checkServerVisibility177(
                ServerVisibilityCase177("PUBLIC", "u1", "u2", listOf("u1"), emptyList(), a)
            )
            for (b in META_FRAGMENT_POOL177) {
                checkServerVisibility177(
                    ServerVisibilityCase177("PUBLIC", "u1", "u2", listOf("u1"), emptyList(), a + b)
                )
            }
        }
        val ids = listOf("u1", "u2")
        val subsets = listOf(
            emptyList<String>(), listOf("u1"), listOf("u2"), listOf("u1", "u2"),
        )
        for (visibility in listOf("PUBLIC", "CONTACTS", "PRIVATE", "FRIENDS")) {
            for (contacts in subsets) {
                for (blocked in subsets) {
                    checkServerVisibility177(
                        ServerVisibilityCase177(
                            visibility, "u1", "u2", contacts, blocked, "<meta>x</meta>hi"
                        )
                    )
                }
            }
        }
    }
}
