package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto

// 账号设备列表排序：当前设备在前，其余按设备 id 升序；
// 自 SettingsDeviceController 的设备加载收敛。
object AccountDeviceSortPolicy {

    fun sort(devices: List<DeviceInfoDto>): List<DeviceInfoDto> =
        devices.sortedWith(
            compareByDescending<DeviceInfoDto> { it.isCurrent }.thenBy { it.deviceId }
        )
}
