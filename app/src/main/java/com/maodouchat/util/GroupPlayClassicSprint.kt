package com.maodouchat.util

// 竞速反应类（倒计时/冲刺/打卡竞速等）：编解码实现（从 GroupPlayClassicPolicy 逐字搬出，零行为改动）。
internal object GroupPlayClassicSprint {

    fun formatCountdown(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 600)
        return "${GroupPlayPolicy.COUNTDOWN_PREFIX}$s|${hostLabel} started ${s}s countdown"
    }

    fun parseCountdown(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.COUNTDOWN_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COUNTDOWN_PREFIX).substringBefore('|')).toIntOrNull()
    }

    fun formatSpeedChallenge(sec: Int, hostLabel: String): String {
        val s = sec.coerceIn(5, 60)
        return "${GroupPlayPolicy.SPEED_PREFIX}$s|${hostLabel} speed challenge — reply in ${s}s!"
    }

    fun parseSpeedChallenge(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.SPEED_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPEED_PREFIX).substringBefore('|')).toIntOrNull()
    }

    fun formatRapidFire(topic: String, hostLabel: String): String {
        val t = topic.trim().take(40)
        return "${GroupPlayPolicy.RAPID_FIRE_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} rapid-fire — name 5 in 20s!"
    }

    fun parseRapidFire(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RAPID_FIRE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RAPID_FIRE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatLightning(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${GroupPlayPolicy.LIGHTNING_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} lightning round"
    }

    fun parseLightning(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LIGHTNING_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LIGHTNING_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSyncClap(count: String, hostLabel: String): String {
        val c = count.trim().take(4)
        return "${GroupPlayPolicy.SYNC_CLAP_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} sync clap x$c"
    }

    fun parseSyncClap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SYNC_CLAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SYNC_CLAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTempoTap(beat: String, hostLabel: String): String {
        val b = beat.trim().take(12)
        return "${GroupPlayPolicy.TEMPO_TAP_PREFIX}${GroupPlayFieldEscape.esc(b)}|${hostLabel} tempo tap"
    }

    fun parseTempoTap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TEMPO_TAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TEMPO_TAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatInviteRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.INVITE_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} invite race"
    }

    fun parseInviteRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.INVITE_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.INVITE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMentionMayhem(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.MENTION_MAYHEM_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mention mayhem"
    }

    fun parseMentionMayhem(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MENTION_MAYHEM_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MENTION_MAYHEM_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatLinkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.LINK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} link hunt"
    }

    fun parseLinkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LINK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LINK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatNudgeDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.NUDGE_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} nudge dash"
    }

    fun parseNudgeDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.NUDGE_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.NUDGE_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatCodeCheck(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CODE_CHECK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} code check"
    }

    fun parseCodeCheck(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CODE_CHECK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CODE_CHECK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTrustSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.TRUST_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} trust sprint"
    }

    fun parseTrustSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TRUST_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TRUST_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFocusSprint(window: String, hostLabel: String): String {
        val w = window.trim().take(10)
        return "${GroupPlayPolicy.FOCUS_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} focus sprint"
    }

    fun parseFocusSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FOCUS_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FOCUS_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatQrQuest(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.QR_QUEST_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} qr quest"
    }

    fun parseQrQuest(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.QR_QUEST_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.QR_QUEST_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatContactSwap(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CONTACT_SWAP_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} contact swap"
    }

    fun parseContactSwap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CONTACT_SWAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CONTACT_SWAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatScanSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SCAN_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} scan sprint"
    }

    fun parseScanSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SCAN_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SCAN_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSpoilerRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SPOILER_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} spoiler race"
    }

    fun parseSpoilerRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPOILER_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPOILER_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatBlurBattle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.BLUR_BATTLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} blur battle"
    }

    fun parseBlurBattle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BLUR_BATTLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BLUR_BATTLE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatDownloadDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.DOWNLOAD_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} download dash"
    }

    fun parseDownloadDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DOWNLOAD_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DOWNLOAD_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPinDrop(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PIN_DROP_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pin drop"
    }

    fun parsePinDrop(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PIN_DROP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PIN_DROP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFileRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FILE_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} file relay"
    }

    fun parseFileRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FILE_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FILE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMapDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MAP_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} map dash"
    }

    fun parseMapDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MAP_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MAP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatVaultLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VAULT_LOCK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} vault lock"
    }

    fun parseVaultLock(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VAULT_LOCK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VAULT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatWatermarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.WATERMARK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} watermark hunt"
    }

    fun parseWatermarkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WATERMARK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WATERMARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSecureSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SECURE_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} secure sprint"
    }

    fun parseSecureSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SECURE_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SECURE_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
