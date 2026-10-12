package com.maodouchat.server.repository

// 设备目录簇：成员函数的薄委托，真实逻辑在 DeviceRegistry 与各 key store 里。

fun SignalKeyRepository.touchDevice(userId: String, deviceId: Int, deviceName: String? = null) =
    deviceRegistry.touchDevice(userId, deviceId, deviceName)

fun SignalKeyRepository.confirmDevice(
    userId: String,
    deviceId: Int,
    approverDeviceId: Int,
    signatureBase64: String,
): ConfirmDeviceResult =
    deviceRegistry.confirmDevice(userId, deviceId, approverDeviceId, signatureBase64)

fun SignalKeyRepository.updateDeviceName(userId: String, deviceId: Int, deviceName: String): Boolean =
    deviceRegistry.updateDeviceName(userId, deviceId, deviceName)

fun SignalKeyRepository.getIdentityKey(userId: String, deviceId: Int): String? =
    identityKeyStore.getIdentityKey(userId, deviceId)

fun SignalKeyRepository.getDeviceId(userId: String, deviceId: Int): Int? =
    identityKeyStore.getDeviceId(userId, deviceId)

fun SignalKeyRepository.isAuthSessionBoundToDevice(
    userId: String,
    authSessionId: String,
    deviceId: Int,
): Boolean =
    deviceRegistry.isAuthSessionBoundToDevice(userId, authSessionId, deviceId)

fun SignalKeyRepository.getDeviceIdForAuthSession(authSessionId: String): Int? =
    deviceRegistry.getDeviceIdForAuthSession(authSessionId)

fun SignalKeyRepository.getDeviceInfos(
    userId: String,
    currentDeviceId: Int? = null,
    includePending: Boolean = false,
): List<DeviceInfo> =
    deviceRegistry.getDeviceInfos(userId, currentDeviceId, includePending)

fun SignalKeyRepository.deleteDeviceAndRevokeSessionsGuarded(
    userId: String,
    deviceId: Int,
): DeleteDeviceOutcome =
    deviceRegistry.deleteDeviceAndRevokeSessionsGuarded(userId, deviceId)

fun SignalKeyRepository.deleteDeviceGuarded(userId: String, deviceId: Int): DeleteDeviceResult =
    deviceRegistry.deleteDeviceGuarded(userId, deviceId)

// 清理已消费 prekey（consumed_pre_key）超过保留期的行。消费后保留一小段窗口
// 以容忍乱序/重放，超期后删除防止无限累积。由 Routing.kt 的周期清理循环调用；默认 30 天。
fun SignalKeyRepository.purgeConsumedPreKeys(retentionDays: Int = 30): Int =
    preKeyStore.purgeConsumedPreKeys(retentionDays)
