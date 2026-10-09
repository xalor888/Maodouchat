package com.maodouchat.util

// 媒体类（照片/视频/语音玩法）：编解码实现（从 GroupPlayModePolicy 逐字搬出，零行为改动）。
internal object GroupPlayModeMedia {

    fun formatPhotoRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PHOTO_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} photo race"
    }

    fun parsePhotoRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PHOTO_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PHOTO_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatClipDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CLIP_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} clip dash"
    }

    fun parseClipDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CLIP_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CLIP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFrameHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FRAME_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} frame hunt"
    }

    fun parseFrameHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FRAME_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FRAME_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatVoiceRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VOICE_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} voice race"
    }

    fun parseVoiceRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VOICE_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VOICE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPixelQuest(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PIXEL_QUEST_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pixel quest"
    }

    fun parsePixelQuest(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PIXEL_QUEST_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PIXEL_QUEST_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatAssistCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.ASSIST_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} assist circle"
    }

    fun formatGifRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.GIF_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} gif relay"
    }

    fun parseGifRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.GIF_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.GIF_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatVoiceRing(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VOICE_RING_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} voice ring"
    }

    fun parseVoiceRing(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VOICE_RING_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VOICE_RING_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatVideoStage(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VIDEO_STAGE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} video stage"
    }

    fun parseVideoStage(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VIDEO_STAGE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VIDEO_STAGE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRingDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.RING_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} ring dash"
    }

    fun parseRingDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RING_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RING_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRingChoir(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.RING_CHOIR_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} ring choir"
    }

    fun parseRingChoir(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RING_CHOIR_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RING_CHOIR_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSoundWave(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SOUND_WAVE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} sound wave"
    }

    fun parseSoundWave(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SOUND_WAVE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SOUND_WAVE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSnapGuard(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SNAP_GUARD_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} snap guard"
    }

    fun parseSnapGuard(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SNAP_GUARD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SNAP_GUARD_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
