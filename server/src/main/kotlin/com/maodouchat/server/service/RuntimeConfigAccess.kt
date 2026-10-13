package com.maodouchat.server.service

// 准入与通用安全簇：注册/维护/发送者密封/bot/IP 封禁/应用安全开关（原 RuntimeConfigSecurity 扩展按域拆出，零行为改动）。

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
