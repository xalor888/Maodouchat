package com.maodouchat.server.service

// 安全/隐私/注册开关簇：原 RuntimeConfigService 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.config.ServerConfig

fun RuntimeConfigService.isRegistrationAllowed(): Boolean =
    getBoolean(KEY_ALLOW_REGISTRATION, ServerConfig.allowRegistration)


fun RuntimeConfigService.isMaintenanceMode(): Boolean =
    getBoolean(KEY_MAINTENANCE_MODE, false)


fun RuntimeConfigService.isSealedSenderEnabled(): Boolean =
    getBoolean(KEY_SEALED_SENDER_ENABLED, true)


fun RuntimeConfigService.isBotsAllowed(): Boolean =
    getBoolean(KEY_ALLOW_BOTS, true)


fun RuntimeConfigService.maxBotsPerUser(): Int =
    getInt(KEY_MAX_BOTS_PER_USER, 20).coerceIn(1, 200)


fun RuntimeConfigService.ipBlocklist(): Set<String> =
    get(KEY_IP_BLOCKLIST)
        .split(",", "\n", ";", " ")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()


fun RuntimeConfigService.isIpBlocked(ip: String?): Boolean {
    if (ip.isNullOrBlank()) return false
    val list = ipBlocklist()
    if (list.isEmpty()) return false
    if (ip in list) return true
    // simple prefix match for CIDR-ish "1.2.3." entries
    return list.any { rule -> rule.endsWith(".") && ip.startsWith(rule) }
}


fun RuntimeConfigService.isPqxdhPreviewEnabled(): Boolean =
    getBoolean(KEY_PQXDH_PREVIEW, false)


fun RuntimeConfigService.minAppVersion(): String = get(KEY_MIN_APP_VERSION).ifBlank { "0" }


fun RuntimeConfigService.isAppLockEnabled(): Boolean =
    getBoolean(KEY_APP_LOCK_ENABLED, true)


fun RuntimeConfigService.isSafetyCodeEnabled(): Boolean =
    getBoolean(KEY_SAFETY_CODE_ENABLED, true)


fun RuntimeConfigService.isSecretChatEnabled(): Boolean =
    getBoolean(KEY_SECRET_CHAT_ENABLED, true)


fun RuntimeConfigService.isScreenSecureRuntimeEnabled(): Boolean =
    getBoolean(KEY_SCREEN_SECURE_RUNTIME_ENABLED, true)


fun RuntimeConfigService.isScreenshotDetectEnabled(): Boolean =
    getBoolean(KEY_SCREENSHOT_DETECT_ENABLED, true)


fun RuntimeConfigService.isRecentsExclusionEnabled(): Boolean =
    getBoolean(KEY_RECENTS_EXCLUSION_ENABLED, true)


fun RuntimeConfigService.isCaptureAlertEnabled(): Boolean =
    getBoolean(KEY_CAPTURE_ALERT_ENABLED, true)


fun RuntimeConfigService.isQrCodeEnabled(): Boolean =
    getBoolean(KEY_QR_CODE_ENABLED, true)


fun RuntimeConfigService.isSecretCopyBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_COPY_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretMediaExportBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretForwardBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_FORWARD_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretChatExportBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretAutoDisappearEnabled(): Boolean =
    getBoolean(KEY_SECRET_AUTO_DISAPPEAR_ENABLED, true)


fun RuntimeConfigService.isSecretLinkPreviewBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretExternalLinkBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED, false)


fun RuntimeConfigService.isSecretNotifPreviewBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretListPreviewBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretReactionBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_REACTION_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretStarBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_STAR_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretTypingBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_TYPING_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretReadReceiptBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretPresenceBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_PRESENCE_BLOCK_ENABLED, true)


fun RuntimeConfigService.isSecretLastSeenBlockEnabled(): Boolean =
    getBoolean(KEY_SECRET_LAST_SEEN_BLOCK_ENABLED, true)


// ═══ B2 密聊防泄漏扩展（Surface #71–#78）═══
// 这些是「客户端能力门」：仅控制客户端是否展示/执行对应本地防护，
// 服务端只存开关位，不接触密聊明文（密聊内容全程 E2EE）。
fun RuntimeConfigService.isSecretScreenshotBurnEnabled(): Boolean =
    getBoolean(KEY_SECRET_SCREENSHOT_BURN_ENABLED, true)


fun RuntimeConfigService.isSecretAutoDestroyEnabled(): Boolean =
    getBoolean(KEY_SECRET_AUTO_DESTROY_ENABLED, true)


fun RuntimeConfigService.isSecretForwardWhitelistEnabled(): Boolean =
    getBoolean(KEY_SECRET_FORWARD_WHITELIST_ENABLED, true)


fun RuntimeConfigService.isSecretSimChangeProtectionEnabled(): Boolean =
    getBoolean(KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED, true)


fun RuntimeConfigService.isSecret2faGateEnabled(): Boolean =
    getBoolean(KEY_SECRET_2FA_GATE_ENABLED, false)


fun RuntimeConfigService.isSecretNewDeviceRiskEnabled(): Boolean =
    getBoolean(KEY_SECRET_NEW_DEVICE_RISK_ENABLED, true)


fun RuntimeConfigService.isSecretDeviceVerifyEnabled(): Boolean =
    getBoolean(KEY_SECRET_DEVICE_VERIFY_ENABLED, true)


fun RuntimeConfigService.isSecretSessionNoticeEnabled(): Boolean =
    getBoolean(KEY_SECRET_SESSION_NOTICE_ENABLED, true)
