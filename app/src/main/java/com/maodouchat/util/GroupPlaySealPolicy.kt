package com.maodouchat.util

/**
 * 群玩法的「隐私开关」一族（G88 从 `GroupPlayPolicy.kt` 拆出）。
 *
 * 这一族共 13 组 `formatXxx` / `parseXxx`，模式完全一致：
 * 负载是 `PREFIX + esc(mode) + "|" + hostLabel + " 开关名"`，
 * 解析时反向取 mode。历史上它们和骰子、投票、猜词等混在一个 2298 行的对象里，
 * 按职责拆出后，`GroupPlayPolicy` 只保留同名委托方法（调用方零改动）。
 *
 * 命名对应关系（legacy 消息渲染需要，不能改）：
 * LinkLock / PreviewMute / UrlFence / NotifMask / ListBlur / TraySeal / ReactLock /
 * StarSeal / MetaFence / TypingSeal / ReadSeal / PresenceSeal / LastSeenSeal。
 */
internal object GroupPlaySealPolicy {

    /** 与 [GroupPlayPolicy] 同一套转义：`|` 与 `^` 是负载分隔符，必须转义。 */
    private fun esc(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unesc(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    const val LINK_LOCK_PREFIX = "LINKLOCK:"
    const val PREVIEW_MUTE_PREFIX = "PREVIEWMUTE:"
    const val URL_FENCE_PREFIX = "URLFENCE:"
    const val NOTIF_MASK_PREFIX = "NOTIFMASK:"
    const val LIST_BLUR_PREFIX = "LISTBLUR:"
    const val TRAY_SEAL_PREFIX = "TRAYSEAL:"
    const val REACT_LOCK_PREFIX = "REACTLOCK:"
    const val STAR_SEAL_PREFIX = "STARSEAL:"
    const val META_FENCE_PREFIX = "METAFENCE:"
    const val TYPING_SEAL_PREFIX = "TYPINGSEAL:"
    const val READ_SEAL_PREFIX = "READSEAL:"
    const val PRESENCE_SEAL_PREFIX = "PRESENCESEAL:"
    const val LASTSEEN_SEAL_PREFIX = "LASTSEENSEAL:"

    fun formatLinkLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LINK_LOCK_PREFIX}${esc(m)}|${hostLabel} link lock"
    }
    fun parseLinkLock(content: String): String? {
        if (!content.startsWith(LINK_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(LINK_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPreviewMute(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PREVIEW_MUTE_PREFIX}${esc(m)}|${hostLabel} preview mute"
    }
    fun parsePreviewMute(content: String): String? {
        if (!content.startsWith(PREVIEW_MUTE_PREFIX)) return null
        return unesc(content.removePrefix(PREVIEW_MUTE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatUrlFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${URL_FENCE_PREFIX}${esc(m)}|${hostLabel} url fence"
    }
    fun parseUrlFence(content: String): String? {
        if (!content.startsWith(URL_FENCE_PREFIX)) return null
        return unesc(content.removePrefix(URL_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatNotifMask(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${NOTIF_MASK_PREFIX}${esc(m)}|${hostLabel} notif mask"
    }
    fun parseNotifMask(content: String): String? {
        if (!content.startsWith(NOTIF_MASK_PREFIX)) return null
        return unesc(content.removePrefix(NOTIF_MASK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatListBlur(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LIST_BLUR_PREFIX}${esc(m)}|${hostLabel} list blur"
    }
    fun parseListBlur(content: String): String? {
        if (!content.startsWith(LIST_BLUR_PREFIX)) return null
        return unesc(content.removePrefix(LIST_BLUR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTraySeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${TRAY_SEAL_PREFIX}${esc(m)}|${hostLabel} tray seal"
    }
    fun parseTraySeal(content: String): String? {
        if (!content.startsWith(TRAY_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(TRAY_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReactLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${REACT_LOCK_PREFIX}${esc(m)}|${hostLabel} react lock"
    }
    fun parseReactLock(content: String): String? {
        if (!content.startsWith(REACT_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(REACT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStarSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${STAR_SEAL_PREFIX}${esc(m)}|${hostLabel} star seal"
    }
    fun parseStarSeal(content: String): String? {
        if (!content.startsWith(STAR_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(STAR_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMetaFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${META_FENCE_PREFIX}${esc(m)}|${hostLabel} meta fence"
    }
    fun parseMetaFence(content: String): String? {
        if (!content.startsWith(META_FENCE_PREFIX)) return null
        return unesc(content.removePrefix(META_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTypingSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${TYPING_SEAL_PREFIX}${esc(m)}|${hostLabel} typing seal"
    }
    fun parseTypingSeal(content: String): String? {
        if (!content.startsWith(TYPING_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(TYPING_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReadSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${READ_SEAL_PREFIX}${esc(m)}|${hostLabel} read seal"
    }
    fun parseReadSeal(content: String): String? {
        if (!content.startsWith(READ_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(READ_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPresenceSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PRESENCE_SEAL_PREFIX}${esc(m)}|${hostLabel} presence seal"
    }
    fun parsePresenceSeal(content: String): String? {
        if (!content.startsWith(PRESENCE_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(PRESENCE_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLastSeenSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LASTSEEN_SEAL_PREFIX}${esc(m)}|${hostLabel} last seen seal"
    }
    fun parseLastSeenSeal(content: String): String? {
        if (!content.startsWith(LASTSEEN_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(LASTSEEN_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }}
