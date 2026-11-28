package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [UserMergePolicy] (checklist line 931,
 * 56th block). Test-only, zero production changes.
 *
 * The oracle state is a verbatim-field mirror of User; mergeOracle
 * re-implements the copy() transition by hand. Blank means Kotlin
 * isBlank (NBSP counts as blank, NEL does not), and only a strictly
 * positive incoming lastSeen wins. Random inputs use Random(42), fresh
 * per @Test.
 */
class UserMergePolicyFuzzTest {

// ---------- C2 UserMergePolicy oracle ----------

// Verbatim-field mirror of User, so the oracle never touches the
// production type; the test file converts between the two for comparison.
private data class OracleUser(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val email: String = "",
    val isOnline: Boolean = false,
    val status: String = "",
    val nickname: String? = null,
    val lastSeen: Long = 0
)

// Hex codec for state fields, local to this fragment: Parse56.kt is
// scratch-only and is not compiled with the repo tests, so the oracle
// state codec cannot call encStr/decStr from there.
private fun encHexStr(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun decHexStr(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun encOracleUser(u: OracleUser): String = listOf(
    "S" + encHexStr(u.id), "S" + encHexStr(u.name),
    if (u.avatar == null) "N" else "S" + encHexStr(u.avatar),
    "S" + encHexStr(u.email),
    if (u.isOnline) "1" else "0", "S" + encHexStr(u.status),
    if (u.nickname == null) "N" else "S" + encHexStr(u.nickname),
    u.lastSeen.toString()
).joinToString(",")

private fun decOracleUser(s: String): OracleUser {
    val p = s.split(",")
    return OracleUser(
        id = decHexStr(p[0].substring(1)),
        name = decHexStr(p[1].substring(1)),
        avatar = if (p[2] == "N") null else decHexStr(p[2].substring(1)),
        email = decHexStr(p[3].substring(1)),
        isOnline = p[4] == "1",
        status = decHexStr(p[5].substring(1)),
        nickname = if (p[6] == "N") null else decHexStr(p[6].substring(1)),
        lastSeen = p[7].toLong()
    )
}

private fun encNullableOracleUser(u: OracleUser?): String =
    if (u == null) "N" else "S" + encOracleUser(u)

private fun decNullableOracleUser(f: String): OracleUser? =
    if (f == "N") null else decOracleUser(f.substring(1))

private fun randomOracleUser(rnd: Random): OracleUser = OracleUser(
    id = randomString(rnd, 24),
    name = randomString(rnd, 24),
    avatar = if (rnd.nextInt(4) == 0) null else randomString(rnd, 24),
    email = randomString(rnd, 24),
    isOnline = rnd.nextBoolean(),
    status = randomString(rnd, 90),
    nickname = if (rnd.nextInt(4) == 0) null else randomString(rnd, 24),
    lastSeen = randomLong(rnd)
)

private fun mergeOracle(incoming: OracleUser, existing: OracleUser?): OracleUser =
    incoming.copy(
        nickname = incoming.nickname?.takeIf { it.isNotBlank() } ?: existing?.nickname,
        avatar = incoming.avatar?.takeIf { it.isNotBlank() } ?: existing?.avatar,
        email = incoming.email.takeIf { it.isNotBlank() } ?: existing?.email ?: "",
        isOnline = if (incoming.isOnline) true else existing?.isOnline ?: false,
        status = incoming.status.takeIf { it.isNotBlank() } ?: existing?.status ?: "",
        lastSeen = if (incoming.lastSeen > 0) incoming.lastSeen else existing?.lastSeen ?: 0
    )

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomNullableString(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else randomString(rnd)

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

// User ids cluster around the 16-code-unit abbreviate boundary.
private fun randomUserId(rnd: Random): String {
    val len = when (rnd.nextInt(10)) {
        0 -> 0; 1 -> 1; 2 -> 8; 3 -> 15; 4 -> 16; 5 -> 17; 6 -> 18; 7 -> 24
        8 -> 32
        else -> rnd.nextInt(0, 41)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

    // Test-only converter between the oracle user and the production type.
    private fun OracleUser.toProduction(): User = User(
        id, name, avatar, email, isOnline, status, nickname, lastSeen
    )

    private fun User.toOracle(): OracleUser = OracleUser(
        id, name, avatar, email, isOnline, status, nickname, lastSeen
    )

    @Test
    fun mergeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val incoming = randomOracleUser(rnd)
            val existing = if (rnd.nextInt(4) == 0) null else randomOracleUser(rnd)
            assertEquals(
                mergeOracle(incoming, existing).toProduction(),
                UserMergePolicy.merge(incoming.toProduction(), existing?.toProduction())
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        val existing = User(
            id = "e1", name = "Old", avatar = "old.png", email = "old@x.com",
            isOnline = true, status = "old status", nickname = "oldnick",
            lastSeen = 1000L
        )
        // Incoming non-blank fields win wholesale.
        val full = User(
            id = "n1", name = "New", avatar = "new.png", email = "new@x.com",
            isOnline = false, status = "new status", nickname = "newnick",
            lastSeen = 2000L
        )
        val mergedFull = UserMergePolicy.merge(full, existing)
        assertEquals("n1", mergedFull.id)
        assertEquals("New", mergedFull.name)
        assertEquals("new.png", mergedFull.avatar)
        assertEquals("new@x.com", mergedFull.email)
        // isOnline is the exception to "incoming wins": incoming false
        // defers to the existing value (true here), it never forces false.
        assertEquals(true, mergedFull.isOnline)
        assertEquals("new status", mergedFull.status)
        assertEquals("newnick", mergedFull.nickname)
        assertEquals(2000L, mergedFull.lastSeen)
        // Incoming blank / null fields fall back to the existing ones;
        // id and name always come from the incoming user.
        val sparse = User(
            id = "n2", name = "", avatar = null, email = "   ",
            isOnline = false, status = "", nickname = "  ", lastSeen = 0L
        )
        val mergedSparse = UserMergePolicy.merge(sparse, existing)
        assertEquals("n2", mergedSparse.id)
        assertEquals("", mergedSparse.name)
        assertEquals("old.png", mergedSparse.avatar)
        assertEquals("old@x.com", mergedSparse.email)
        assertEquals(true, mergedSparse.isOnline)
        assertEquals("old status", mergedSparse.status)
        assertEquals("oldnick", mergedSparse.nickname)
        assertEquals(1000L, mergedSparse.lastSeen)
        // No existing user: blanks fall back to defaults, not to null.
        val mergedNone = UserMergePolicy.merge(sparse, null)
        assertEquals(null, mergedNone.avatar)
        assertEquals("", mergedNone.email)
        assertEquals(false, mergedNone.isOnline)
        assertEquals("", mergedNone.status)
        assertEquals(null, mergedNone.nickname)
        assertEquals(0L, mergedNone.lastSeen)
        // NBSP-only counts as blank (falls back); NEL-only counts as content.
        val nbsp = User(id = "n3", name = "N", nickname = "\u00A0", status = "\u00A0", email = "\u00A0")
        val mergedNbsp = UserMergePolicy.merge(nbsp, existing)
        assertEquals("oldnick", mergedNbsp.nickname)
        assertEquals("old status", mergedNbsp.status)
        assertEquals("old@x.com", mergedNbsp.email)
        val nel = User(id = "n4", name = "N", nickname = "\u0085", status = "\u0085", email = "\u0085")
        val mergedNel = UserMergePolicy.merge(nel, existing)
        assertEquals("\u0085", mergedNel.nickname)
        assertEquals("\u0085", mergedNel.status)
        assertEquals("\u0085", mergedNel.email)
        // lastSeen: only strictly positive incoming wins; 0 / negative
        // fall back, and a null existing user yields 0.
        assertEquals(5L, UserMergePolicy.merge(full.copy(lastSeen = 5L), existing).lastSeen)
        assertEquals(1000L, UserMergePolicy.merge(full.copy(lastSeen = 0L), existing).lastSeen)
        assertEquals(1000L, UserMergePolicy.merge(full.copy(lastSeen = -5L), existing).lastSeen)
        assertEquals(0L, UserMergePolicy.merge(full.copy(lastSeen = -5L), null).lastSeen)
        // isOnline: incoming true always wins; incoming false defers.
        assertEquals(true, UserMergePolicy.merge(full.copy(isOnline = true), existing.copy(isOnline = false)).isOnline)
        assertEquals(false, UserMergePolicy.merge(full.copy(isOnline = false), existing.copy(isOnline = false)).isOnline)
        assertEquals(false, UserMergePolicy.merge(full.copy(isOnline = false), null).isOnline)
    }
}
