package com.maodouchat.server.repository

/**
 * 只读可加密设备目录接口（B03 领域接口）。
 * V2 信箱与群聊广播只依赖只读设备目录，不依赖宽密钥仓库。
 */
interface EncryptableDeviceDirectory {
    /** 批量获取用户集合中状态为 CONFIRMED 且包含必要密钥的设备 (userId, deviceId) 集合。 */
    fun getConfirmedDeviceTargets(userIds: Collection<String>): Set<Pair<String, Int>>

    /** 获取指定用户的设备 ID 列表。 */
    fun getDeviceIds(userId: String, confirmedOnly: Boolean = true): List<Int>

    /** 判断指定设备是否处于已确认 (CONFIRMED) 状态。 */
    fun isDeviceConfirmed(userId: String, deviceId: Int): Boolean
}
