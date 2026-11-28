package com.maodouchat.server.service

import com.maodouchat.server.model.NotificationSettingsResponse
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isTransientHttpFailure restated by hand: 429 and any 5xx are
// transient, everything else is not.
internal fun isTransientHttpFailureOracle177(status: Int): Boolean =
    status == 429 || status >= 500

// ttlFor restated by hand: incoming calls get the short TTL, every
// other type (including null) gets the message TTL.
internal fun ttlForOracle177(type: String?): String =
    if (type == "INCOMING_CALL") "60s" else "86400s"

// isInDoNotDisturb restated by hand: the window is disabled, empty,
// or the local minute (floor-divided epoch minute plus the clamped
// offset, modulo one day) falls outside it. The floor division is
// what keeps pre-1970 stamps on the right day.
internal fun isInDoNotDisturbOracle177(
    dndEnabled: Boolean,
    startMinute: Int,
    endMinute: Int,
    offsetMinutes: Int,
    nowMillis: Long,
): Boolean {
    if (!dndEnabled) return false
    val start = startMinute.coerceIn(0, 1439)
    val end = endMinute.coerceIn(0, 1439)
    if (start == end) return false
    val safeOffset = offsetMinutes.coerceIn(-18 * 60, 18 * 60)
    val minute = Math.floorMod(Math.floorDiv(nowMillis, 60_000L) + safeOffset, 1440L).toInt()
    return if (start < end) minute in start until end else minute >= start || minute < end
}

// isPermanentTokenFailure restated by hand: only 400/404 can be
// permanent, and only when the lowercased body names a dead token.
internal fun isPermanentTokenFailureOracle177(status: Int, body: String): Boolean {
    if (status != 400 && status != 404) return false
    val text = body.lowercase()
    if ("unregistered" in text ||
        "not_found" in text ||
        "notfound" in text ||
        "registration-token-not-registered" in text ||
        "requested entity was not found" in text ||
        "sender_id_mismatch" in text ||
        "senderidmismatch" in text
    ) {
        return true
    }
    val invalidArg = "invalid_argument" in text || "invalidargument" in text
    return invalidArg && ("registration" in text || "token" in text)
}

// Production isTransientHttpFailure classifies the FCM HTTP status.
internal fun isTransientHttpFailureProduction177(status: Int): Boolean =
    isTransientHttpFailure(status)

// Production ttlFor picks the FCM time-to-live for the message type.
internal fun ttlForProduction177(type: String?): String = ttlFor(type)

// Production isInDoNotDisturb checks the DND window at the given time.
internal fun isInDoNotDisturbProduction177(
    settings: NotificationSettingsResponse,
    offsetMinutes: Int,
    nowMillis: Long,
): Boolean = isInDoNotDisturb(settings, offsetMinutes, nowMillis)

// Production isPermanentTokenFailure recognises dead FCM tokens.
internal fun isPermanentTokenFailureProduction177(status: Int, body: String): Boolean =
    isPermanentTokenFailure(status, body)

// ---------- generators ----------

internal data class FcmCase177(
    val status: Int,
    val type: String?,
    val dndEnabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val offsetMinutes: Int,
    val nowMillis: Long,
    val body: String,
)

internal val FCM_STATUS_POOL177: List<Int> = listOf(
    -1, 0, 200, 301, 400, 401, 403, 404, 408, 429, 500, 502, 503, 999,
)

internal val FCM_TYPE_POOL177: List<String?> = listOf(
    null, "INCOMING_CALL", "MESSAGE", "incoming_call", "INCOMING_CALL ", " CALL", "",
    "INCOMING_CALL\u00a0",
)

internal val FCM_MINUTE_POOL177: List<Int> = listOf(
    -1, 0, 1, 419, 420, 421, 719, 720, 1319, 1320, 1321, 1439, 1440, 2000,
)

internal val FCM_OFFSET_POOL177: List<Int> = listOf(
    -2000, -1080, -1079, -330, -1, 0, 1, 330, 1079, 1080, 2000,
)

internal val FCM_NOW_POOL177: List<Long> = listOf(
    0L, -1L, -60_000L, -60_001L, 86_399_000L, 45_000_000L,
    1_699_999_999_999L, -1_699_999_999_999L,
    Long.MAX_VALUE / 2, Long.MIN_VALUE / 2,
)

internal val FCM_BODY_POOL177: List<String> = listOf(
    "", "ok", "UNREGISTERED", "Unregistered", "not_found", "NOTFOUND",
    "registration-token-not-registered",
    "Requested entity was not found", "SENDER_ID_MISMATCH", "senderidmismatch",
    "invalid_argument", "INVALID_ARGUMENT", "invalidargument",
    "invalid_argument: registration token expired",
    "invalid_argument: something else", "invalidargument token",
    "registration", "token", "no token here", "unregistered user",
    "  unregistered  ", "unregistered\ntoken", "Ünregistered",
    "ınvalid_argument registration", "ﬀ invalid_argument token",
    "not_found!", "error: notfound.", "registration-token-not-registered.",
    "a".repeat(200),
)

internal fun randomFcmCase177(rnd: Random): FcmCase177 {
    fun randomBody(): String {
        if (rnd.nextInt(3) == 0) {
            return FCM_BODY_POOL177[rnd.nextInt(FCM_BODY_POOL177.size)]
        }
        val alphabet = "unregis_tofd_ \nTOKEN-"
        val len = rnd.nextInt(0, 24)
        val sb = StringBuilder()
        repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }
    return FcmCase177(
        status = if (rnd.nextInt(3) == 0) {
            FCM_STATUS_POOL177[rnd.nextInt(FCM_STATUS_POOL177.size)]
        } else {
            rnd.nextInt(-10, 1200)
        },
        type = FCM_TYPE_POOL177[rnd.nextInt(FCM_TYPE_POOL177.size)],
        dndEnabled = rnd.nextBoolean(),
        startMinute = if (rnd.nextInt(2) == 0) {
            FCM_MINUTE_POOL177[rnd.nextInt(FCM_MINUTE_POOL177.size)]
        } else {
            rnd.nextInt(-100, 1600)
        },
        endMinute = if (rnd.nextInt(2) == 0) {
            FCM_MINUTE_POOL177[rnd.nextInt(FCM_MINUTE_POOL177.size)]
        } else {
            rnd.nextInt(-100, 1600)
        },
        offsetMinutes = if (rnd.nextInt(2) == 0) {
            FCM_OFFSET_POOL177[rnd.nextInt(FCM_OFFSET_POOL177.size)]
        } else {
            rnd.nextInt(-2000, 2000)
        },
        nowMillis = if (rnd.nextInt(3) == 0) {
            FCM_NOW_POOL177[rnd.nextInt(FCM_NOW_POOL177.size)]
        } else {
            rnd.nextLong(-2_000_000_000_000L, 2_000_000_000_000L)
        },
        body = randomBody(),
    )
}

internal fun escJsonFcm177(s: String): String {
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

internal fun escJsonOrNullFcm177(s: String?): String =
    if (s == null) "null" else escJsonFcm177(s)

internal fun fcmWireJson177(c: FcmCase177): String =
    "{\"status\":${c.status}," +
        "\"type\":${escJsonOrNullFcm177(c.type)}," +
        "\"dndEnabled\":${c.dndEnabled}," +
        "\"startMinute\":${c.startMinute}," +
        "\"endMinute\":${c.endMinute}," +
        "\"offsetMinutes\":${c.offsetMinutes}," +
        "\"nowMillis\":${c.nowMillis}," +
        "\"body\":${escJsonFcm177(c.body)}}"

internal fun fcmProjectionJson177(
    transient: Boolean,
    ttl: String,
    dnd: Boolean,
    permanent: Boolean,
): String =
    "{\"transient\":$transient,\"ttl\":${escJsonFcm177(ttl)}," +
        "\"dnd\":$dnd,\"permanent\":$permanent}"

class FcmPushDeliveryPolicyFuzzTest {

    private fun checkFcm177(c: FcmCase177) {
        val settings = NotificationSettingsResponse(
            dndEnabled = c.dndEnabled,
            dndStartMinute = c.startMinute,
            dndEndMinute = c.endMinute,
        )
        assertEquals(
            fcmProjectionJson177(
                isTransientHttpFailureOracle177(c.status),
                ttlForOracle177(c.type),
                isInDoNotDisturbOracle177(
                    c.dndEnabled, c.startMinute, c.endMinute, c.offsetMinutes, c.nowMillis
                ),
                isPermanentTokenFailureOracle177(c.status, c.body),
            ),
            fcmProjectionJson177(
                isTransientHttpFailureProduction177(c.status),
                ttlForProduction177(c.type),
                isInDoNotDisturbProduction177(settings, c.offsetMinutes, c.nowMillis),
                isPermanentTokenFailureProduction177(c.status, c.body),
            ),
            "fcm($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every status against every type and every body keyword; the
        // DND window is pinned at the day edges and midnight crossings.
        for (status in FCM_STATUS_POOL177) {
            for (type in FCM_TYPE_POOL177) {
                checkFcm177(
                    FcmCase177(status, type, false, 1320, 420, 0, 0L, "")
                )
            }
            for (body in FCM_BODY_POOL177) {
                checkFcm177(
                    FcmCase177(status, "MESSAGE", false, 1320, 420, 0, 0L, body)
                )
            }
        }
        for (start in FCM_MINUTE_POOL177) {
            for (end in FCM_MINUTE_POOL177) {
                for (offset in listOf(-1080, 0, 480)) {
                    checkFcm177(
                        FcmCase177(200, "MESSAGE", true, start, end, offset, 45_000_000L, "")
                    )
                }
            }
        }
        for (now in FCM_NOW_POOL177) {
            for (offset in FCM_OFFSET_POOL177) {
                checkFcm177(
                    FcmCase177(200, "MESSAGE", true, 1320, 420, offset, now, "")
                )
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(177002)
        repeat(4000) { checkFcm177(randomFcmCase177(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // A full day at one-minute steps for the cross-midnight window,
        // at three offsets, plus every status in 395..505 against the
        // keyword bodies.
        for (minute in 0 until 1440) {
            val now = minute.toLong() * 60_000L
            for (offset in listOf(-480, 0, 540)) {
                checkFcm177(
                    FcmCase177(200, "MESSAGE", true, 1320, 420, offset, now, "")
                )
            }
        }
        for (status in 395..505) {
            for (body in listOf("unregistered", "invalid_argument token", "ok")) {
                checkFcm177(
                    FcmCase177(status, "MESSAGE", false, 1320, 420, 0, 0L, body)
                )
            }
        }
        for (start in listOf(0, 719, 720, 1439)) {
            for (end in listOf(0, 719, 720, 1439)) {
                checkFcm177(
                    FcmCase177(200, "MESSAGE", true, start, end, 0, 0L, "")
                )
            }
        }
    }
}
