package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationSoundPolicyTest {

    @Test
    fun messageSoundRequiresBothFlagAndPreference() {
        assertTrue(
            NotificationSoundPolicy.messageSoundEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = true
            )
        )
        // 服务端 flag 关闭（管理员禁用）→ 即使用户偏好开启也不发声
        assertFalse(
            NotificationSoundPolicy.messageSoundEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = true
            )
        )
        // 用户偏好关闭 → 不发声
        assertFalse(
            NotificationSoundPolicy.messageSoundEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = false
            )
        )
        assertFalse(
            NotificationSoundPolicy.messageSoundEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = false
            )
        )
    }

    @Test
    fun ringtoneRequiresBothFlagAndPreference() {
        assertTrue(
            NotificationSoundPolicy.ringtoneEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = true
            )
        )
        assertFalse(
            NotificationSoundPolicy.ringtoneEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = true
            )
        )
        assertFalse(
            NotificationSoundPolicy.ringtoneEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = false
            )
        )
    }
}
