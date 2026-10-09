package com.maodouchat.util

// 隐私安全类（导出锁/封印/遮罩等）：编解码实现（从 GroupPlayModePolicy 逐字搬出，零行为改动）。
internal object GroupPlayModePrivacy {

    fun formatLeakSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.LEAK_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} leak sprint"
    }

    fun parseLeakSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LEAK_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LEAK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPreviewMask(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PREVIEW_MASK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} preview mask"
    }

    fun parsePreviewMask(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PREVIEW_MASK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PREVIEW_MASK_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatFadeCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FADE_CIRCLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} fade circle"
    }

    fun parseFadeCircle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FADE_CIRCLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FADE_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRecentsHide(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.RECENTS_HIDE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} recents hide"
    }

    fun parseRecentsHide(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RECENTS_HIDE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RECENTS_HIDE_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatCertRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CERT_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} cert relay"
    }

    fun parseCertRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CERT_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CERT_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFadeTimer(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FADE_TIMER_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} fade timer"
    }

    fun parseFadeTimer(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FADE_TIMER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FADE_TIMER_PREFIX).substringBefore('|')).ifBlank { null }
    }

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
