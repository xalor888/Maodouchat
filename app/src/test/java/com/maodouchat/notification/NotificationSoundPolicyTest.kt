package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun inAppReceiveToneRequiresFlagsNotificationsAndSoundPreference() {
        assertTrue(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = false,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = false,
                notificationSoundFlag = true,
                soundPreference = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = false,
            )
        )
        // 服务端 notification_sound flag 关闭时，应用内短提示音也必须静音
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = false,
                soundPreference = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
                appInForeground = true,
            )
        )
    }

    @Test
    fun traySoundSilentWhileAppInForeground() {
        assertTrue(
            NotificationSoundPolicy.traySoundAllowed(
                appInForeground = false,
                preferenceSoundEnabled = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.traySoundAllowed(
                appInForeground = true,
                preferenceSoundEnabled = true,
            )
        )
        assertFalse(
            NotificationSoundPolicy.traySoundAllowed(
                appInForeground = false,
                preferenceSoundEnabled = false,
            )
        )
    }

    @Test
    fun trayMessageSoundRequiresFlagPreferenceAndUnmutedChat() {
        assertTrue(
            NotificationSoundPolicy.trayMessageSoundEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = true,
                chatMuted = false,
            )
        )
        assertFalse(
            NotificationSoundPolicy.trayMessageSoundEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = true,
                chatMuted = false,
            )
        )
        assertFalse(
            NotificationSoundPolicy.trayMessageSoundEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = false,
                chatMuted = false,
            )
        )
        assertFalse(
            NotificationSoundPolicy.trayMessageSoundEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = true,
                chatMuted = true,
            )
        )
    }

    @Test
    fun ringtoneBothOffStaysSilent() {
        // 补齐 ringtone 2x2 矩阵最后一格。
        assertFalse(
            NotificationSoundPolicy.ringtoneEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = false
            )
        )
    }

    @Test
    fun traySoundForegroundWithPreferenceOffIsSilent() {
        // 补齐 traySoundAllowed 2x2 矩阵最后一格。
        assertFalse(
            NotificationSoundPolicy.traySoundAllowed(
                appInForeground = true,
                preferenceSoundEnabled = false,
            )
        )
    }

    @Test
    fun inAppToneDefaultsToBackground() {
        // appInForeground 缺省为 false：不传即视为后台。
        assertTrue(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = true,
                notificationsEnabled = true,
                notificationSoundFlag = true,
                soundPreference = true,
            )
        )
    }

    @Test
    fun soundDecisionsAreDeterministic() {
        assertEquals(
            NotificationSoundPolicy.messageSoundEnabled(true, true),
            NotificationSoundPolicy.messageSoundEnabled(true, true)
        )
        assertEquals(
            NotificationSoundPolicy.trayMessageSoundEnabled(true, true, false),
            NotificationSoundPolicy.trayMessageSoundEnabled(true, true, false)
        )
        assertEquals(
            NotificationSoundPolicy.traySoundAllowed(false, true),
            NotificationSoundPolicy.traySoundAllowed(false, true)
        )
    }

    @Test
    fun ringtoneGateIsIndependentFromMessageSoundGate() {
        // 来电铃声走独立的服务端 flag：消息音被管理员关掉不影响响铃。
        assertTrue(
            NotificationSoundPolicy.ringtoneEnabled(
                runtimeFlagEnabled = true,
                userPreferenceEnabled = true
            )
        )
        assertFalse(
            NotificationSoundPolicy.messageSoundEnabled(
                runtimeFlagEnabled = false,
                userPreferenceEnabled = true
            )
        )
    }

    @Test
    fun trayMessageSoundMuteOverridesAllFlagCombinations() {
        // 会话静音时，flag/偏好的任何组合都不发声（静音维度优先）。
        for (runtime in listOf(true, false)) {
            for (pref in listOf(true, false)) {
                assertFalse(
                    NotificationSoundPolicy.trayMessageSoundEnabled(
                        runtimeFlagEnabled = runtime,
                        userPreferenceEnabled = pref,
                        chatMuted = true,
                    ),
                    "muted chat must stay silent for flags ($runtime, $pref)"
                )
            }
        }
    }

    @Test
    fun trayMessageSoundRemainingMatrixCellsStaySilent() {
        // 补齐 trayMessageSoundEnabled 2x2x2 矩阵剩余格。
        listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, true),
            Triple(true, false, true),
        ).forEach { (runtime, pref, muted) ->
            assertFalse(
                NotificationSoundPolicy.trayMessageSoundEnabled(runtime, pref, muted),
                "($runtime, $pref, $muted) must stay silent"
            )
        }
    }

    @Test
    fun inAppToneAllGatesOffSilent() {
        // 四门全关：边界全灭情形。
        assertFalse(
            NotificationSoundPolicy.inAppReceiveToneEnabled(
                inAppSoundsFlag = false,
                notificationsEnabled = false,
                notificationSoundFlag = false,
                soundPreference = false,
            )
        )
    }

    @Test
    fun ringtoneAndMessageSoundShareAndSemantics() {
        // 两者都是 flag && 偏好：四格真值表恒一致。
        for (flag in listOf(true, false)) {
            for (pref in listOf(true, false)) {
                assertEquals(
                    NotificationSoundPolicy.messageSoundEnabled(flag, pref),
                    NotificationSoundPolicy.ringtoneEnabled(flag, pref),
                    "($flag, $pref) semantics must match"
                )
            }
        }
    }

    @Test
    fun remainingSoundPoliciesAreDeterministic() {
        assertEquals(
            NotificationSoundPolicy.inAppReceiveToneEnabled(true, true, true, true),
            NotificationSoundPolicy.inAppReceiveToneEnabled(true, true, true, true)
        )
        assertEquals(
            NotificationSoundPolicy.ringtoneEnabled(false, true),
            NotificationSoundPolicy.ringtoneEnabled(false, true)
        )
    }
}
