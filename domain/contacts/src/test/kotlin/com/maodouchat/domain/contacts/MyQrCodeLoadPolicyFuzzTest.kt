package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [MyQrCodeLoadPolicy] (checklist line 931,
 * 57th block). Test-only, zero production changes.
 *
 * The oracle state is a verbatim-field mirror of User; planOracle
 * re-implements the plan() guards by hand. A remote failure or a closed
 * session gate never contributes live data, and a blank live name falls
 * back to the local name while the live avatar still wins. Random inputs
 * use Random(42), fresh per @Test.
 */
class MyQrCodeLoadPolicyFuzzTest {

// ---------- OracleUser: verbatim-field mirror of User ----------

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

// Hex codec for state fields, local to this fragment: Parse57.kt is
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

// ---------- C2 MyQrCodeLoadPolicy oracle ----------

private sealed interface OracleDecision {
    data class Reject(val reason: String) : OracleDecision
    data class Allow(
        val targetUserId: String,
        val displayName: String,
        val avatar: String?
    ) : OracleDecision
}

private fun planOracle(
    qrEnabled: Boolean,
    token: String,
    userId: String,
    local: OracleUser?,
    remote: Result<OracleUser>?,
    sessionMayContinue: Boolean
): OracleDecision {
    if (!qrEnabled) return OracleDecision.Reject("DISABLED")
    if (token.isBlank() && userId.isBlank()) return OracleDecision.Reject("NO_SESSION")
    val live = remote?.getOrNull()?.takeIf { sessionMayContinue }
    val name = live?.name?.takeIf { it.isNotBlank() } ?: local?.name.orEmpty()
    val avatar = live?.avatar ?: local?.avatar
    return OracleDecision.Allow(userId, name, avatar)
}

private fun encOracleDecision(d: OracleDecision): String = when (d) {
    is OracleDecision.Reject -> "R:" + d.reason
    is OracleDecision.Allow -> "A:" + encHexStr(d.targetUserId) + "," +
        encHexStr(d.displayName) + "," +
        if (d.avatar == null) "N" else "S" + encHexStr(d.avatar)
}

private fun encNullableResult(r: Result<OracleUser>?): String = when {
    r == null -> "N"
    r.isFailure -> "F"
    else -> "S" + encOracleUser(r.getOrNull()!!)
}

private fun decNullableResult(f: String): Result<OracleUser>? = when (f) {
    "N" -> null
    "F" -> Result.failure(IllegalStateException("remote failed"))
    else -> Result.success(decOracleUser(f.substring(1)))
}

// Token / userId inputs cluster on blank: empty, spaces, NBSP (blank in
// Kotlin) and NEL (not blank, so it reaches Allow).
private fun randomTokenOrId(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> ""
    1 -> when (rnd.nextInt(4)) {
        0 -> " "
        1 -> "   "
        2 -> "\u00A0"
        else -> "\u0085"
    }
    else -> randomString(rnd, 24)
}

private fun randomRemote(rnd: Random): Result<OracleUser>? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> Result.failure(IllegalStateException("remote failed"))
    else -> Result.success(randomOracleUser(rnd))
}

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

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

    // Test-only converters between the oracle user / decision and the
    // production types.
    private fun OracleUser.toProduction(): User = User(
        id, name, avatar, email, isOnline, status, nickname, lastSeen
    )

    private fun MyQrCodeDecision.toOracle(): OracleDecision = when (this) {
        is MyQrCodeDecision.Reject -> OracleDecision.Reject(reason.name)
        is MyQrCodeDecision.Allow -> OracleDecision.Allow(targetUserId, displayName, avatar)
    }

    @Test
    fun planRandom() {
        val rnd = Random(42)
        repeat(300) {
            val qrEnabled = rnd.nextInt(4) != 0
            val token = randomTokenOrId(rnd)
            val userId = randomTokenOrId(rnd)
            val local = if (rnd.nextInt(4) == 0) null else randomOracleUser(rnd)
            val remote = randomRemote(rnd)
            val sessionMayContinue = rnd.nextBoolean()
            assertEquals(
                planOracle(qrEnabled, token, userId, local, remote, sessionMayContinue),
                MyQrCodeLoadPolicy.plan(
                    qrEnabled, token, userId,
                    local?.toProduction(),
                    remote?.map { it.toProduction() },
                    sessionMayContinue
                ).toOracle()
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        val local = User(id = "u1", name = "Local", avatar = "l.png")
        val live = User(id = "u1", name = "Live", avatar = "r.png")
        val liveBlankName = User(id = "u1", name = "  ", avatar = "r.png")
        val liveNoAvatar = User(id = "u1", name = "Live", avatar = null)
        val remoteFail = Result.failure<User>(IllegalStateException("remote failed"))
        // qrEnabled=false rejects before anything else is looked at.
        assertEquals(
            OracleDecision.Reject("DISABLED"),
            MyQrCodeLoadPolicy.plan(false, "", "", null, null).toOracle()
        )
        assertEquals(
            OracleDecision.Reject("DISABLED"),
            MyQrCodeLoadPolicy.plan(false, "t", "u", local, Result.success(live)).toOracle()
        )
        // Blank token AND blank userId: no session. NBSP counts as blank.
        assertEquals(
            OracleDecision.Reject("NO_SESSION"),
            MyQrCodeLoadPolicy.plan(true, "", "", null, null).toOracle()
        )
        assertEquals(
            OracleDecision.Reject("NO_SESSION"),
            MyQrCodeLoadPolicy.plan(true, " ", "  ", local, Result.success(live)).toOracle()
        )
        assertEquals(
            OracleDecision.Reject("NO_SESSION"),
            MyQrCodeLoadPolicy.plan(true, "\u00A0", "", null, null).toOracle()
        )
        // NEL is not blank, so a NEL token reaches Allow (with an empty
        // userId echoed back and no name anywhere to show).
        assertEquals(
            OracleDecision.Allow("", "", null),
            MyQrCodeLoadPolicy.plan(true, "\u0085", "", null, null).toOracle()
        )
        // One non-blank side is enough; the userId is echoed verbatim.
        assertEquals(
            OracleDecision.Allow("", "", null),
            MyQrCodeLoadPolicy.plan(true, "tok", "", null, null).toOracle()
        )
        assertEquals(
            OracleDecision.Allow("  u  ", "", null),
            MyQrCodeLoadPolicy.plan(true, "", "  u  ", null, null).toOracle()
        )
        // Live name wins; live avatar wins.
        assertEquals(
            OracleDecision.Allow("u1", "Live", "r.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, Result.success(live)).toOracle()
        )
        // Live name blank: name falls back to local, avatar still live.
        assertEquals(
            OracleDecision.Allow("u1", "Local", "r.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, Result.success(liveBlankName)).toOracle()
        )
        // Live avatar null: avatar falls back to local.
        assertEquals(
            OracleDecision.Allow("u1", "Live", "l.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, Result.success(liveNoAvatar)).toOracle()
        )
        // Remote failure: no live data, local fills both fields.
        assertEquals(
            OracleDecision.Allow("u1", "Local", "l.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, remoteFail).toOracle()
        )
        // Session gate closed: the remote user is ignored entirely.
        assertEquals(
            OracleDecision.Allow("u1", "Local", "l.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, Result.success(live), false).toOracle()
        )
        // No remote, no local: empty name, null avatar.
        assertEquals(
            OracleDecision.Allow("u1", "", null),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", null, null).toOracle()
        )
        // No remote: local fills both fields.
        assertEquals(
            OracleDecision.Allow("u1", "Local", "l.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", local, null).toOracle()
        )
        // Live name wins even with no local user at all.
        assertEquals(
            OracleDecision.Allow("u1", "Live", "r.png"),
            MyQrCodeLoadPolicy.plan(true, "t", "u1", null, Result.success(live)).toOracle()
        )
    }
}
