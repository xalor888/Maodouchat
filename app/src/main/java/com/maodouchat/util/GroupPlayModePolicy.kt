package com.maodouchat.util

/**
 * 媒体 / 系统 / 隐私类群玩法模式的编解码（G328c 从 `GroupPlayPolicy` 搬出，实现搬走、调用方零改动）。
 *
 * 与 `GroupPlayClassicPolicy` 的分界在前缀常量块处；实测两段的前缀常量**零共享**
 * （107 / 57，无跨段引用），所以常量跟着各自的函数一起搬。
 */
internal object GroupPlayModePolicy {

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
    fun formatSummaryCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SUMMARY_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} summary circle"
    }
    fun parseSummaryCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SUMMARY_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SUMMARY_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRewriteRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.REWRITE_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} rewrite relay"
    }
    fun parseRewriteRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REWRITE_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REWRITE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPromptSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PROMPT_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} prompt sprint"
    }
    fun parsePromptSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PROMPT_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PROMPT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSuggestCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SUGGEST_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} suggest circle"
    }
    fun parseSuggestCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SUGGEST_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SUGGEST_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVoiceRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VOICE_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} voice race"
    }
    fun parseVoiceRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VOICE_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VOICE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReplySprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.REPLY_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} reply sprint"
    }
    fun parseReplySprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REPLY_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REPLY_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun parseAssistCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ASSIST_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ASSIST_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatDecisionDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.DECISION_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} decision dash"
    }
    fun parseDecisionDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DECISION_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DECISION_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatDocHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.DOC_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} doc hunt"
    }
    fun parseDocHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DOC_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DOC_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatMeaningRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MEANING_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} meaning race"
    }
    fun parseMeaningRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MEANING_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MEANING_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatInsightSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.INSIGHT_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} insight sprint"
    }
    fun parseInsightSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.INSIGHT_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.INSIGHT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatGifRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.GIF_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} gif relay"
    }
    fun parseGifRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.GIF_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.GIF_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MARK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mark hunt"
    }
    fun parseMarkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MARK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLeakSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.LEAK_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} leak sprint"
    }
    fun parseLeakSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LEAK_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LEAK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatWallPick(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.WALL_PICK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} wall pick"
    }
    fun parseWallPick(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WALL_PICK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WALL_PICK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFontRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FONT_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} font race"
    }
    fun parseFontRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FONT_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FONT_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatThemeSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.THEME_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} theme sprint"
    }
    fun parseThemeSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.THEME_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.THEME_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatUnreadRush(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.UNREAD_RUSH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} unread rush"
    }
    fun parseUnreadRush(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.UNREAD_RUSH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.UNREAD_RUSH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRingChoir(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.RING_CHOIR_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} ring choir"
    }
    fun parseRingChoir(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RING_CHOIR_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RING_CHOIR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatAlertSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.ALERT_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} alert sprint"
    }
    fun parseAlertSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ALERT_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ALERT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSoundWave(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SOUND_WAVE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} sound wave"
    }
    fun parseSoundWave(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SOUND_WAVE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SOUND_WAVE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPreviewMask(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PREVIEW_MASK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} preview mask"
    }
    fun parsePreviewMask(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PREVIEW_MASK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PREVIEW_MASK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBeepDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.BEEP_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} beep dash"
    }
    fun parseBeepDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BEEP_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BEEP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPushRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PUSH_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} push race"
    }
    fun parsePushRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PUSH_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PUSH_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRemindCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.REMIND_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} remind circle"
    }
    fun parseRemindCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REMIND_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REMIND_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWakeSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.WAKE_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} wake sprint"
    }
    fun parseWakeSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WAKE_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WAKE_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatQuietHour(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.QUIET_HOUR_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} quiet hour"
    }
    fun parseQuietHour(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.QUIET_HOUR_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.QUIET_HOUR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatOfflineHint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.OFFLINE_HINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} offline hint"
    }
    fun parseOfflineHint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.OFFLINE_HINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.OFFLINE_HINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFallbackDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FALLBACK_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} fallback dash"
    }
    fun parseFallbackDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FALLBACK_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FALLBACK_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatClickBeat(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CLICK_BEAT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} click beat"
    }
    fun parseClickBeat(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CLICK_BEAT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CLICK_BEAT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBuzzRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.BUZZ_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} buzz relay"
    }
    fun parseBuzzRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BUZZ_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BUZZ_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFeelSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FEEL_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} feel sprint"
    }
    fun parseFeelSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FEEL_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FEEL_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSlideRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SLIDE_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} slide race"
    }
    fun parseSlideRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SLIDE_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SLIDE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFadeCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FADE_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} fade circle"
    }
    fun parseFadeCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FADE_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FADE_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSpringDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SPRING_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} spring dash"
    }
    fun parseSpringDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPRING_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPRING_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSnapGuard(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SNAP_GUARD_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} snap guard"
    }
    fun parseSnapGuard(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SNAP_GUARD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SNAP_GUARD_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRecentsHide(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.RECENTS_HIDE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} recents hide"
    }
    fun parseRecentsHide(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RECENTS_HIDE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RECENTS_HIDE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatShieldSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SHIELD_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} shield sprint"
    }
    fun parseShieldSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SHIELD_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SHIELD_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCopyLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.COPY_LOCK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} copy lock"
    }
    fun parseCopyLock(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COPY_LOCK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COPY_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatExportSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.EXPORT_SEAL_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} export seal"
    }
    fun parseExportSeal(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EXPORT_SEAL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EXPORT_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLeakWall(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.LEAK_WALL_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} leak wall"
    }
    fun parseLeakWall(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LEAK_WALL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LEAK_WALL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatForwardSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FORWARD_SEAL_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} forward seal"
    }
    fun parseForwardSeal(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FORWARD_SEAL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FORWARD_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatChatExportLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CHAT_EXPORT_LOCK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} chat export lock"
    }
    fun parseChatExportLock(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CHAT_EXPORT_LOCK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CHAT_EXPORT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVaultFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VAULT_FENCE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} vault fence"
    }
    fun parseVaultFence(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VAULT_FENCE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VAULT_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSealSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SEAL_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} seal sprint"
    }
    fun parseSealSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SEAL_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SEAL_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPqxdhDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PQXDH_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pqxdh dash"
    }
    fun parsePqxdhDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PQXDH_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PQXDH_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCertRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CERT_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} cert relay"
    }
    fun parseCertRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CERT_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CERT_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMarkSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MARK_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mark sprint"
    }
    fun parseMarkSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MARK_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MARK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFadeTimer(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FADE_TIMER_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} fade timer"
    }
    fun parseFadeTimer(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FADE_TIMER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FADE_TIMER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStampRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.STAMP_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} stamp relay"
    }
    fun parseStampRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.STAMP_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.STAMP_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    // ─── 隐私开关一族（G88 拆至 GroupPlaySealPolicy，此处仅保留同名委托，调用方零改动） ───
    fun formatLinkLock(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatLinkLock(mode, hostLabel)
    fun parseLinkLock(content: String): String? = GroupPlaySealPolicy.parseLinkLock(content)
    fun formatPreviewMute(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatPreviewMute(mode, hostLabel)
    fun parsePreviewMute(content: String): String? = GroupPlaySealPolicy.parsePreviewMute(content)
    fun formatUrlFence(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatUrlFence(mode, hostLabel)
    fun parseUrlFence(content: String): String? = GroupPlaySealPolicy.parseUrlFence(content)
    fun formatNotifMask(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatNotifMask(mode, hostLabel)
    fun parseNotifMask(content: String): String? = GroupPlaySealPolicy.parseNotifMask(content)
    fun formatListBlur(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatListBlur(mode, hostLabel)
    fun parseListBlur(content: String): String? = GroupPlaySealPolicy.parseListBlur(content)
    fun formatTraySeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatTraySeal(mode, hostLabel)
    fun parseTraySeal(content: String): String? = GroupPlaySealPolicy.parseTraySeal(content)
    fun formatReactLock(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatReactLock(mode, hostLabel)
    fun parseReactLock(content: String): String? = GroupPlaySealPolicy.parseReactLock(content)
    fun formatStarSeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatStarSeal(mode, hostLabel)
    fun parseStarSeal(content: String): String? = GroupPlaySealPolicy.parseStarSeal(content)
    fun formatMetaFence(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatMetaFence(mode, hostLabel)
    fun parseMetaFence(content: String): String? = GroupPlaySealPolicy.parseMetaFence(content)
    fun formatTypingSeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatTypingSeal(mode, hostLabel)
    fun parseTypingSeal(content: String): String? = GroupPlaySealPolicy.parseTypingSeal(content)
    fun formatReadSeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatReadSeal(mode, hostLabel)
    fun parseReadSeal(content: String): String? = GroupPlaySealPolicy.parseReadSeal(content)
    fun formatPresenceSeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatPresenceSeal(mode, hostLabel)
}
