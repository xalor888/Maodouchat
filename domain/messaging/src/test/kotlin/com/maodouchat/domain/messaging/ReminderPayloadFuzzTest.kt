package com.maodouchat.domain.messaging

import java.util.Calendar
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Reminder / location / transcription restated by hand. Ground truth,
// read from AiTaskReminderNextAllowedPolicy.kt / AiTaskDndPolicy.kt /
// LocationPayloadPolicy.kt / LocationPayload.kt /
// AgentTranscriptionPolicy.kt:
//   - nextAllowedTime: dnd off -> null; current minute-of-day from
//     Calendar (system zone); end-of-window is today at endMinute
//     (minute precision, Calendar-normalized), +1 day when not strictly
//     after now; then the DND rule: all three minute values coerced to
//     0..1439 first; equal bounds mean an empty window; a day window is
//     [start, end) and an overnight window is minute >= start ||
//     minute < end; returns endOfWindowMs only when inside the window
//     AND strictly after nowMs, else null.
//   - LocationPayload.static: accuracy kept only when hasAccuracy;
//     capturedAt is time when > 0, else now. live: same static mapping,
//     then label / live=true / liveUntil / sessionId on top.
//   - extractText: JSONObject(payload).optString("text"); any parse
//     failure -> "" (runCatching). optString: missing/null -> "",
//     numbers/bools -> toString, objects/arrays -> compact toString.
internal fun isInQuietHoursOracle186(
    minuteOfDay: Int,
    startMinute: Int,
    endMinute: Int,
    dndRuntimeEnabled: Boolean = true
): Boolean {
    if (!dndRuntimeEnabled) return false
    val minute = minuteOfDay.coerceIn(0, 1439)
    val start = startMinute.coerceIn(0, 1439)
    val end = endMinute.coerceIn(0, 1439)
    if (start == end) return false
    return if (start < end) {
        minute in start until end
    } else {
        minute >= start || minute < end
    }
}

internal fun nextAllowedTimeDndOracle186(
    nowMs: Long,
    minuteOfDay: Int,
    startMinute: Int,
    endMinute: Int,
    endOfWindowMs: Long
): Long? {
    if (!isInQuietHoursOracle186(minuteOfDay, startMinute, endMinute)) return null
    return if (endOfWindowMs > nowMs) endOfWindowMs else null
}

internal fun nextAllowedTimeOracle186(
    nowMs: Long,
    dndEnabled: Boolean,
    startMinute: Int,
    endMinute: Int
): Long? {
    if (!dndEnabled) return null
    val current = Calendar.getInstance().apply { timeInMillis = nowMs }
    val nowMinute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE)
    val endOfWindow = (current.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, endMinute / 60)
        set(Calendar.MINUTE, endMinute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return nextAllowedTimeDndOracle186(nowMs, nowMinute, startMinute, endMinute, endOfWindow)
}

internal fun locationStaticOracle186(
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    hasAccuracy: Boolean,
    time: Long,
    now: Long
): LocationPayload = LocationPayload(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.takeIf { hasAccuracy },
    capturedAt = time.takeIf { it > 0 } ?: now
)

internal fun locationLiveOracle186(
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    hasAccuracy: Boolean,
    time: Long,
    now: Long,
    label: String,
    liveUntil: Long,
    sessionId: String
): LocationPayload = locationStaticOracle186(
    latitude, longitude, accuracy, hasAccuracy, time, now
).copy(
    label = label,
    live = true,
    liveUntil = liveUntil,
    sessionId = sessionId
)

internal fun extractTextOracle186(payload: String): String =
    runCatching { JSONObject(payload).optString("text") }.getOrNull().orEmpty()

internal fun escJsonReminder186(s: String): String {
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
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

internal fun locationPayloadWireJson186(p: LocationPayload): String =
    "{\"latitude\":${escJsonReminder186(p.latitude.toString())}," +
        "\"longitude\":${escJsonReminder186(p.longitude.toString())}," +
        "\"accuracyMeters\":${p.accuracyMeters?.let { escJsonReminder186(it.toString()) } ?: "null"}," +
        "\"label\":${escJsonReminder186(p.label)}," +
        "\"capturedAt\":${p.capturedAt}," +
        "\"live\":${p.live}," +
        "\"liveUntil\":${p.liveUntil?.toString() ?: "null"}," +
        "\"sessionId\":${p.sessionId?.let { escJsonReminder186(it) } ?: "null"}}"

internal fun optLongWireJson186(v: Long?): String = v?.toString() ?: "null"

// ---------- production ----------

internal fun nextAllowedTimeProduction186(
    nowMs: Long,
    dndEnabled: Boolean,
    startMinute: Int,
    endMinute: Int
): Long? = AiTaskReminderNextAllowedPolicy.nextAllowedTime(nowMs, dndEnabled, startMinute, endMinute)

internal fun locationStaticProduction186(
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    hasAccuracy: Boolean,
    time: Long,
    now: Long
): LocationPayload = LocationPayloadPolicy.static(latitude, longitude, accuracy, hasAccuracy, time, now)

internal fun locationLiveProduction186(
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    hasAccuracy: Boolean,
    time: Long,
    now: Long,
    label: String,
    liveUntil: Long,
    sessionId: String
): LocationPayload = LocationPayloadPolicy.live(
    latitude, longitude, accuracy, hasAccuracy, time, now, label, liveUntil, sessionId
)

internal fun extractTextProduction186(payload: String): String =
    AgentTranscriptionPolicy.extractText(payload)

// ---------- generators ----------

internal val REMIND_NOWS186: List<Long> = listOf(
    0L, 1L, -1L, 1000L, 1_700_000_000_000L, 1_764_000_000_000L,
    1_764_000_000_000L - 60_000L, 1_764_000_000_000L + 3_600_000L,
    Long.MIN_VALUE / 2, Long.MAX_VALUE / 2
)

internal val REMIND_MINUTES186: List<Int> = listOf(
    0, 1, 59, 60, 419, 420, 421, 719, 720, 721, 1350, 1438, 1439,
    1440, 1500, 2880, -1, -30, -60, Int.MIN_VALUE, Int.MAX_VALUE
)

internal val LOCATION_DOUBLES186: List<String> = listOf(
    "0.0", "-0.0", "1.0", "-1.0", "39.9", "116.4", "-90.0", "90.0",
    "-180.0", "180.0", "0.1", "0.30000000000000004", "1.0E7", "1.0E-7",
    "1.7976931348623157E308", "4.9E-324", "NaN", "Infinity", "-Infinity",
    "123456.789", "1.0E16", "0.001", "0.0001"
)

internal val LOCATION_FLOATS186: List<String> = listOf(
    "0.0", "-0.0", "1.0", "1.5", "0.1", "3.4028235E38", "1.4E-45",
    "NaN", "Infinity", "-Infinity", "100.0", "0.001", "1.0E7"
)

internal val TRANSCRIBE_PAYLOADS186: List<String> = listOf(
    "",
    " ",
    "{}",
    "{\"text\":\"hello\"}",
    "{\"text\":\"\"}",
    "{\"text\":\"  \"}",
    "{\"text\":42}",
    "{\"text\":1.5}",
    "{\"text\":1e2}",
    "{\"text\":true}",
    "{\"text\":null}",
    "{\"text\":[1,2]}",
    "{\"text\":{\"a\":1}}",
    "{\"text\":{\"b\":2,\"a\":1}}",
    "{\"text\":\"a\\nb\"}",
    "{\"text\":\"caf\\u00e9\"}",
    "{\"text\":\"\\uD83D\\uDE00\"}",
    "{\"TEXT\":\"upper\"}",
    "{\"text\":\"first\",\"text\":\"second\"}",
    "{\"other\":\"x\"}",
    "not json",
    "{oops",
    "[1,2]",
    "123",
    "\"str\"",
    "null",
    "true",
    "{'text':'single'}",
    "{text:unquoted}",
    "{\"text\":'single'}",
    "{\"text\":hello}",
    "{\"text\":01}",
    "{\"text\":NaN}",
    "{\"text\":Infinity}",
    "{\"text\":1,}",
    "{\"text\":\"x\"} trailing",
    "{\"text\":\"x\"} {\"text\":\"y\"}",
    "{\"text\":  \"spaced\"  }",
    "{\"text\":\"\\u0001\"}",
)

internal data class RemindCase186(
    val nowMs: Long,
    val dndEnabled: Boolean,
    val startMinute: Int,
    val endMinute: Int
)

internal data class LocationCase186(
    val which: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val hasAccuracy: Boolean,
    val time: Long,
    val now: Long,
    val label: String,
    val liveUntil: Long,
    val sessionId: String
)

internal data class TranscribeCase186(val payload: String)

internal fun randomRemindCase186(rnd: Random): RemindCase186 = RemindCase186(
    nowMs = listOf(
        REMIND_NOWS186.random(rnd),
        rnd.nextLong(-2_000_000_000_000L, 2_000_000_000_000L)
    ).random(rnd),
    dndEnabled = rnd.nextBoolean(),
    startMinute = REMIND_MINUTES186.random(rnd),
    endMinute = REMIND_MINUTES186.random(rnd)
)

internal fun randomLocationCase186(rnd: Random): LocationCase186 {
    val lat = LOCATION_DOUBLES186.random(rnd).toDouble()
    val lon = LOCATION_DOUBLES186.random(rnd).toDouble()
    return LocationCase186(
        which = listOf("static", "live").random(rnd),
        latitude = lat,
        longitude = lon,
        accuracy = LOCATION_FLOATS186.random(rnd).toFloat(),
        hasAccuracy = rnd.nextBoolean(),
        time = listOf(-1L, 0L, 1L, 1000L, Long.MIN_VALUE, Long.MAX_VALUE).random(rnd),
        now = listOf(0L, 1000L, 1_764_000_000_000L).random(rnd),
        label = listOf("", "home", "  ", "café", "a\nb").random(rnd),
        liveUntil = listOf(0L, -1L, 1000L, Long.MAX_VALUE).random(rnd),
        sessionId = listOf("", "s1", "  ").random(rnd)
    )
}

internal fun randomTranscribeCase186(rnd: Random): TranscribeCase186 {
    val payload = when (rnd.nextInt(0, 4)) {
        0 -> TRANSCRIBE_PAYLOADS186.random(rnd)
        1 -> "{\"text\":${listOf("\"hi\"", "42", "1.5", "true", "null", "[1]", "{\"a\":1}", "\"\"").random(rnd)}}"
        2 -> "{\"text\":\"" + "x".repeat(rnd.nextInt(0, 40)) + "\"}"
        else -> {
            val key = listOf("text", "other", "TEXT", "text ").random(rnd)
            "{\"$key\":\"v${rnd.nextInt(0, 100)}\"}"
        }
    }
    return TranscribeCase186(payload)
}

internal fun remindCaseWireJson186(c: RemindCase186): String =
    "{\"nowMs\":${c.nowMs},\"dndEnabled\":${c.dndEnabled}," +
        "\"startMinute\":${c.startMinute},\"endMinute\":${c.endMinute}}"

internal fun locationCaseWireJson186(c: LocationCase186): String =
    "{\"which\":${escJsonReminder186(c.which)}," +
        "\"latitude\":${escJsonReminder186(c.latitude.toString())}," +
        "\"longitude\":${escJsonReminder186(c.longitude.toString())}," +
        "\"accuracy\":${escJsonReminder186(c.accuracy.toString())}," +
        "\"hasAccuracy\":${c.hasAccuracy}," +
        "\"time\":${c.time},\"now\":${c.now}," +
        "\"label\":${escJsonReminder186(c.label)}," +
        "\"liveUntil\":${c.liveUntil}," +
        "\"sessionId\":${escJsonReminder186(c.sessionId)}}"

internal fun transcribeCaseWireJson186(c: TranscribeCase186): String =
    "{\"payload\":${escJsonReminder186(c.payload)}}"

class ReminderPayloadFuzzTest {

    private fun checkRemind186(c: RemindCase186) {
        assertEquals(
            optLongWireJson186(nextAllowedTimeOracle186(c.nowMs, c.dndEnabled, c.startMinute, c.endMinute)),
            optLongWireJson186(nextAllowedTimeProduction186(c.nowMs, c.dndEnabled, c.startMinute, c.endMinute)),
            "nextAllowedTime($c)"
        )
    }

    private fun checkLocation186(c: LocationCase186) {
        val oracle = if (c.which == "live") {
            locationLiveOracle186(
                c.latitude, c.longitude, c.accuracy, c.hasAccuracy, c.time, c.now,
                c.label, c.liveUntil, c.sessionId
            )
        } else {
            locationStaticOracle186(
                c.latitude, c.longitude, c.accuracy, c.hasAccuracy, c.time, c.now
            )
        }
        val production = if (c.which == "live") {
            locationLiveProduction186(
                c.latitude, c.longitude, c.accuracy, c.hasAccuracy, c.time, c.now,
                c.label, c.liveUntil, c.sessionId
            )
        } else {
            locationStaticProduction186(
                c.latitude, c.longitude, c.accuracy, c.hasAccuracy, c.time, c.now
            )
        }
        assertEquals(locationPayloadWireJson186(oracle), locationPayloadWireJson186(production), "location($c)")
    }

    private fun checkTranscribe186(c: TranscribeCase186) {
        assertEquals(
            escJsonReminder186(extractTextOracle186(c.payload)),
            escJsonReminder186(extractTextProduction186(c.payload)),
            "extractText(${c.payload})"
        )
    }

    @Test
    fun pinnedReminderPayload() {
        for (nowMs in REMIND_NOWS186) {
            for (start in listOf(0, 420, 720, 1350, 1439)) {
                for (end in listOf(0, 420, 480, 720, 1439)) {
                    checkRemind186(RemindCase186(nowMs, true, start, end))
                    checkRemind186(RemindCase186(nowMs, false, start, end))
                }
            }
        }
        for (lat in LOCATION_DOUBLES186) {
            for (lon in LOCATION_DOUBLES186) {
                checkLocation186(
                    LocationCase186(
                        "static", lat.toDouble(), lon.toDouble(), 1.5f, true,
                        1000L, 2000L, "", 0L, ""
                    )
                )
            }
        }
        for (acc in LOCATION_FLOATS186) {
            for (hasAcc in listOf(true, false)) {
                for (time in listOf(-1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    checkLocation186(
                        LocationCase186(
                            "live", 39.9, 116.4, acc.toFloat(), hasAcc,
                            time, 2000L, "home", 3000L, "s1"
                        )
                    )
                }
            }
        }
        for (payload in TRANSCRIBE_PAYLOADS186) {
            checkTranscribe186(TranscribeCase186(payload))
        }
    }

    @Test
    fun randomReminderPayloadMatchProduction() {
        val rnd = Random(186003)
        repeat(1500) { checkRemind186(randomRemindCase186(rnd)) }
        repeat(500) { checkLocation186(randomLocationCase186(rnd)) }
        repeat(500) { checkTranscribe186(randomTranscribeCase186(rnd)) }
    }

    @Test
    fun sweepReminderPayloadBoundaries() {
        // Minute edges around day/overnight windows, at a fixed now.
        val now = 1_764_000_000_000L
        for (start in REMIND_MINUTES186) {
            for (end in listOf(0, 420, 720, 1439, 1440)) {
                checkRemind186(RemindCase186(now, true, start, end))
            }
        }
        for (end in REMIND_MINUTES186) {
            for (start in listOf(0, 420, 720, 1350, 1439)) {
                checkRemind186(RemindCase186(now, true, start, end))
            }
        }
        // nowMs sweep across one local day, minute by minute near midnight.
        for (offset in listOf(0L, -60_000L, -1L, 1L, 60_000L, 3_600_000L, 82_800_000L, 86_400_000L)) {
            checkRemind186(RemindCase186(1_764_000_000_000L + offset, true, 1320, 420))
            checkRemind186(RemindCase186(1_764_000_000_000L + offset, true, 0, 1439))
        }
        // Float accuracy edges.
        for (acc in listOf("0.0", "-0.0", "1.0", "3.4028235E38", "1.4E-45", "NaN", "Infinity")) {
            checkLocation186(
                LocationCase186(
                    "static", 0.0, 0.0, acc.toFloat(), true, 0L, 0L, "", 0L, ""
                )
            )
        }
    }
}
