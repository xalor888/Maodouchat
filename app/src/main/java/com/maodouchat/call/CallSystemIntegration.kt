package com.maodouchat.call

import android.content.Context
import com.maodouchat.service.CallForegroundService
import com.maodouchat.telecom.TelecomHelper

/**
 * P03：通话系统集成端口（前台服务、Telecom 派发、锁屏旗标查询）。
 *
 * ViewModel / Activity 只提交意图；FGS、Telecom、锁屏可见性判定由本适配器对接系统面。
 * ConnectionService 内部仍直接使用 [TelecomHelper] 常量与 extras。
 */
class CallSystemIntegration(
    private val appContext: Context,
    private val startForeground: (contactName: String, isVideo: Boolean, callId: String) -> Unit =
        { name, video, id -> CallForegroundService.start(appContext, name, video, id) },
    private val stopForeground: () -> Unit = { CallForegroundService.stop(appContext) },
    private val activeCallId: () -> String? = { CallForegroundService.getActiveCallId() },
    private val hasPendingIncomingCall: () -> Boolean =
        { IncomingCallCoordinator.peekPending() != null },
    private val placeTelecomIncoming: (
        callerName: String,
        callId: String,
        isVideo: Boolean,
    ) -> Boolean = { name, id, video ->
        TelecomHelper.placeIncomingCall(appContext, name, id, video)
    },
    private val registerTelecomAccount: () -> Unit =
        { TelecomHelper.registerPhoneAccount(appContext) },
) {
    fun startCallForeground(contactName: String, isVideo: Boolean, callId: String) {
        startForeground(contactName, isVideo, callId)
    }

    fun stopCallForeground() {
        stopForeground()
    }

    /** Whether MainActivity may remain visible over the keyguard. */
    fun lockScreenFlagsNeeded(): Boolean =
        CallLockScreenFlagPolicy.shouldEnable(
            activeCallId = activeCallId(),
            hasPendingIncomingCall = hasPendingIncomingCall(),
        )

    /**
     * Dispatch a system incoming-call UI via Telecom.
     * @return false when Telecom is unavailable — caller should fall back to in-app UI.
     */
    fun placeIncomingCall(callerName: String, callId: String, isVideo: Boolean): Boolean =
        placeTelecomIncoming(callerName, callId, isVideo)

    fun registerPhoneAccount() {
        registerTelecomAccount()
    }
}
