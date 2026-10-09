package com.maodouchat.util

// 系统效率类（提醒/冲刺/效率玩法）：编解码实现（从 GroupPlayModePolicy 逐字搬出，零行为改动）。
internal object GroupPlayModeSystem {

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

    fun formatReplySprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.REPLY_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} reply sprint"
    }

    fun parseReplySprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REPLY_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REPLY_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatMarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MARK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mark hunt"
    }

    fun parseMarkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MARK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatAlertSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.ALERT_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} alert sprint"
    }

    fun parseAlertSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ALERT_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ALERT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatSpringDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SPRING_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} spring dash"
    }

    fun parseSpringDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPRING_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPRING_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatShieldSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SHIELD_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} shield sprint"
    }

    fun parseShieldSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SHIELD_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SHIELD_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPqxdhDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PQXDH_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pqxdh dash"
    }

    fun parsePqxdhDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PQXDH_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PQXDH_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMarkSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MARK_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mark sprint"
    }

    fun parseMarkSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MARK_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MARK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
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
}
