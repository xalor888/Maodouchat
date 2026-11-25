// ServerFeatureFlags optBoolean decode (144th block). Oracle
// reimplements optBoolean from org.json semantics: Boolean wins,
// "true"/"false" strings (case-insensitive, no trim) win, everything
// else falls back to the per-key default (three keys default false).
package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private val FLAG_KEYS144 = listOf(
    "appLockEnabled" to true,
    "autoDownloadEnabled" to true,
    "blindWatermarkEnabled" to true,
    "blockReportEnabled" to true,
    "callsEnabled" to true,
    "captureAlertEnabled" to true,
    "chatAnimationsEnabled" to true,
    "chatArchiveEnabled" to true,
    "chatDraftsEnabled" to true,
    "chatExportEnabled" to false,
    "chatFoldersEnabled" to true,
    "chatFontScaleEnabled" to true,
    "chatLockEnabled" to true,
    "chatMuteEnabled" to true,
    "chatPinEnabled" to true,
    "chatWallpaperEnabled" to true,
    "contactCardEnabled" to true,
    "disappearingMessagesEnabled" to true,
    "dndEnabled" to true,
    "fileShareEnabled" to true,
    "friendRequestsEnabled" to true,
    "gifSendEnabled" to true,
    "globalSearchEnabled" to true,
    "groupInvitesEnabled" to true,
    "groupPlayEnabled" to true,
    "hapticsEnabled" to true,
    "imageSendEnabled" to true,
    "inAppSoundsEnabled" to true,
    "linkPreviewEnabled" to true,
    "liveLocationEnabled" to true,
    "markdownEnabled" to true,
    "markedUnreadEnabled" to true,
    "mediaUploadEnabled" to true,
    "mentionsEnabled" to true,
    "messageEditEnabled" to true,
    "messageForwardingEnabled" to true,
    "messagePinEnabled" to true,
    "messageRevokeEnabled" to true,
    "messageStarringEnabled" to true,
    "navTransitionsEnabled" to true,
    "nearbyEnabled" to false,
    "notificationPreviewEnabled" to true,
    "notificationSoundEnabled" to true,
    "nudgeEnabled" to true,
    "pollsEnabled" to true,
    "postsEnabled" to true,
    "presenceEnabled" to true,
    "pushNotificationsEnabled" to true,
    "qrCodeEnabled" to true,
    "reactionsEnabled" to true,
    "readReceiptsEnabled" to true,
    "recentsExclusionEnabled" to true,
    "ringtoneEnabled" to true,
    "safetyCodeEnabled" to true,
    "scheduledMessagesEnabled" to true,
    "screenSecureRuntimeEnabled" to true,
    "screenshotDetectEnabled" to true,
    "sealedSenderEnabled" to true,
    "secretAutoDisappearEnabled" to true,
    "secretChatExportBlockEnabled" to true,
    "secretChatEnabled" to true,
    "secretCopyBlockEnabled" to true,
    "secretExternalLinkBlockEnabled" to false,
    "secretForwardBlockEnabled" to true,
    "secretLinkPreviewBlockEnabled" to true,
    "secretListPreviewBlockEnabled" to true,
    "secretMediaExportBlockEnabled" to true,
    "secretNotifPreviewBlockEnabled" to true,
    "secretReactionBlockEnabled" to true,
    "secretStarBlockEnabled" to true,
    "secretTypingBlockEnabled" to true,
    "secretReadReceiptBlockEnabled" to true,
    "secretPresenceBlockEnabled" to true,
    "secretLastSeenBlockEnabled" to true,
    "silentSendEnabled" to true,
    "spoilerMediaEnabled" to true,
    "staticLocationEnabled" to true,
    "stickersEnabled" to true,
    "taskRemindersEnabled" to true,
    "typingIndicatorsEnabled" to true,
    "unreadPriorityEnabled" to true,
    "videoCallEnabled" to true,
    "videoSendEnabled" to true,
    "viewOnceEnabled" to true,
    "voiceCallEnabled" to true,
    "voiceMessagesEnabled" to true
)

private fun optBooleanOracle144(obj: JSONObject, key: String, default: Boolean): Boolean {
    val v = obj.opt(key) ?: return default
    if (v === JSONObject.NULL) return default
    if (v is Boolean) return v
    if (v is String) {
        if (v.equals("true", ignoreCase = true)) return true
        if (v.equals("false", ignoreCase = true)) return false
    }
    return default
}

private fun flagsOracle144(obj: JSONObject): Map<String, Boolean> =
    FLAG_KEYS144.associate { (k, d) -> k to optBooleanOracle144(obj, k, d) }

// ---------- generators (mirrored by Oracle144 GEN, seed 144002) ----------

private val FLAG_VALUE_POOL144: Array<Any?> = arrayOf(
    true, false, "true", "false", "True", "FALSE", " true", "true ",
    "yes", "1", "", 0, 1, 2, -1, 1.5, null,
)

private fun putScalar144(o: JSONObject, key: String, v: Any?) {
    when (v) {
        null -> o.put(key, JSONObject.NULL)
        is List<*> -> o.put(key, JSONArray(v))
        is Map<*, *> -> o.put(key, JSONObject(v))
        else -> o.put(key, v)
    }
}

private fun randomFlagsObject144(rnd: Random): JSONObject {
    val o = JSONObject()
    for ((k, _) in FLAG_KEYS144) {
        if (rnd.nextInt(4) == 0) continue
        putScalar144(o, k, FLAG_VALUE_POOL144[rnd.nextInt(FLAG_VALUE_POOL144.size)])
    }
    repeat(rnd.nextInt(0, 3)) {
        val v = FLAG_VALUE_POOL144[rnd.nextInt(FLAG_VALUE_POOL144.size)]
        putScalar144(o, "unknownKey$it", v)
    }
    // Occasionally a collection/object value, which must fall back.
    if (rnd.nextInt(6) == 0) {
        val k = FLAG_KEYS144[rnd.nextInt(FLAG_KEYS144.size)].first
        if (rnd.nextInt(2) == 0) o.put(k, JSONArray(listOf(1, 2))) else o.put(k, JSONObject(mapOf("a" to 1)))
    }
    return o
}

class ServerFeatureFlagsFuzzTest {

    @Test
    fun pinnedBoundaries() {
        assertEquals(flagsOracle144(JSONObject()), parseServerFeatureFlags(JSONObject()))
        val allTrue = JSONObject()
        val allFalse = JSONObject()
        for ((k, _) in FLAG_KEYS144) {
            allTrue.put(k, true)
            allFalse.put(k, false)
        }
        assertEquals(flagsOracle144(allTrue), parseServerFeatureFlags(allTrue))
        assertEquals(flagsOracle144(allFalse), parseServerFeatureFlags(allFalse))
        val variants = JSONObject()
            .put("appLockEnabled", "True")
            .put("callsEnabled", "TRUE")
            .put("chatLockEnabled", " true")
            .put("markdownEnabled", "true ")
            .put("secretChatEnabled", "yes")
            .put("typingIndicatorsEnabled", "1")
            .put("chatExportEnabled", 1)
            .put("nearbyEnabled", 0)
            .put("secretExternalLinkBlockEnabled", JSONObject.NULL)
            .put("reactionsEnabled", JSONArray(listOf(true)))
            .put("pollsEnabled", JSONObject(mapOf("x" to 1)))
            .put("postsEnabled", 1.5)
            .put("unknownKey", true)
        assertEquals(flagsOracle144(variants), parseServerFeatureFlags(variants))
        val flipped = JSONObject()
        for ((k, d) in FLAG_KEYS144) flipped.put(k, !d)
        assertEquals(flagsOracle144(flipped), parseServerFeatureFlags(flipped))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(144002)
        repeat(1500) {
            val obj = randomFlagsObject144(rnd)
            assertEquals(flagsOracle144(obj), parseServerFeatureFlags(obj))
        }
    }

    @Test
    fun sweepExhaustive() {
        // One key at a time, every pool value.
        for ((k, _) in FLAG_KEYS144) {
            for (v in FLAG_VALUE_POOL144) {
                val o = JSONObject()
                putScalar144(o, k, v)
                assertEquals(flagsOracle144(o), parseServerFeatureFlags(o))
            }
            val oArr = JSONObject().put(k, JSONArray(listOf(1)))
            assertEquals(flagsOracle144(oArr), parseServerFeatureFlags(oArr))
            val oObj = JSONObject().put(k, JSONObject(mapOf("a" to 1)))
            assertEquals(flagsOracle144(oObj), parseServerFeatureFlags(oObj))
        }
        // All keys present, one key flipped to each pool value.
        for ((k, _) in FLAG_KEYS144) {
            for (v in FLAG_VALUE_POOL144) {
                val o = JSONObject()
                for ((k2, d2) in FLAG_KEYS144) o.put(k2, d2)
                putScalar144(o, k, v)
                assertEquals(flagsOracle144(o), parseServerFeatureFlags(o))
            }
        }
    }
}
