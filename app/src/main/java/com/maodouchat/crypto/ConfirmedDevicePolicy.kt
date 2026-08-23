package com.maodouchat.crypto

/**
 * Picks a Signal device to talk to when the caller still uses the legacy default (1).
 * Device 1 is often a leftover PENDING ghost; discovery lists are confirmed-only.
 */
object ConfirmedDevicePolicy {
    const val DEFAULT_DEVICE_ID = 1
    const val MIN_DEVICE_ID = 1
    const val MAX_DEVICE_ID = 255

    fun resolve(
        requestedDeviceId: Int,
        confirmedDeviceIds: List<Int>,
        defaultDeviceId: Int = DEFAULT_DEVICE_ID
    ): Int? {
        val confirmed = confirmedDeviceIds.filter { it in MIN_DEVICE_ID..MAX_DEVICE_ID }
            .distinct()
            .sorted()
        if (requestedDeviceId != defaultDeviceId) {
            return requestedDeviceId.takeIf { it in MIN_DEVICE_ID..MAX_DEVICE_ID }
        }
        if (requestedDeviceId in confirmed) return requestedDeviceId
        return confirmed.minOrNull()
    }
}
