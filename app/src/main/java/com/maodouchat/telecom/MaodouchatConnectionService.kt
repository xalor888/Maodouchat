package com.maodouchat.telecom

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.StatusHints
import android.telecom.TelecomManager
import android.util.Log
import com.maodouchat.MainActivity
import com.maodouchat.call.CallActionBus
import com.maodouchat.call.IncomingCallCoordinator

/**
 * 系统级来电接管的 ConnectionService。
 *
 * 行为：
 * - onCreateIncomingConnection: 根据 TelecomHelper.placeIncomingCall 携带的 extras 构造
 *   一个 self-managed [Connection]，展示来电人名 / 视频 / 音频标识。
 * - onAnswer: 用户在系统通话界面接听。打开 MainActivity 并透传 callId，由 NavGraph 路由到
 *   CallScreen 完成实际 WebRTC 接听。
 * - onReject: 用户拒接。清空 PendingIncomingCall 并通过 CallActionBus 通知挂断。
 * - onDisconnect / onAbort: 超时 / 远端取消。清空 pending。
 *
 * 注意：self-managed 模式下系统不会强制展示原生通话 UI，但当应用前台未渲染来电屏
 * （如锁屏后台）时，系统仍会用本 Connection 提供的 statusHints 渲染通知。
 */
class MaodouchatConnectionService : ConnectionService() {

    companion object {
        const val TAG = "MaodouConnService"

        /**
         * 当前来电 Connection 引用。self-managed 模式下同时只会存在一个来电 Connection，
         * 保存引用以便应用内通话结束时（CallViewModel.endCall）主动销毁，避免系统残留「活跃通话」。
         */
        @Volatile
        internal var activeConnection: MaodouchatConnection? = null
            private set

        /** 应用内通话结束时调用：销毁对应的 Telecom Connection，清除系统级「活跃通话」状态。 */
        fun finishConnection(callId: String) {
            val conn = activeConnection
            if (conn != null && conn.callId == callId) {
                activeConnection = null
                try {
                    conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                    conn.destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "finishConnection failed", e)
                }
            }
        }

        /** 系统驱动的生命周期结束（拒接/挂断/取消）后清除引用。 */
        fun clearActive(callId: String) {
            val conn = activeConnection
            if (conn != null && conn.callId == callId) activeConnection = null
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        connectionRequest: ConnectionRequest?,
    ): Connection {
        val extras = connectionRequest?.extras ?: android.os.Bundle()
        val callerName = extras.getString(TelecomHelper.EXTRA_CALLER_NAME, "未知来电")
        val callId = extras.getString(TelecomHelper.EXTRA_CALL_ID, "")
        val isVideo = extras.getBoolean(TelecomHelper.EXTRA_IS_VIDEO, false)

        Log.i(TAG, "onCreateIncomingConnection: caller=$callerName callId=$callId video=$isVideo")

        val conn = MaodouchatConnection(
            applicationContext = applicationContext,
            callerName = callerName,
            callId = callId,
            isVideo = isVideo,
        )
        // 8.46 修复：覆盖前销毁旧 Connection——否则前一通来电尚未被系统回收时又来新来电，
        // 旧 Connection 永远不 destroy()，系统 Telecom 会残留「幽灵活跃通话」。
        // 8.49 修复：同 callId 的重复派发（FCM at-least-once 重投）同样销毁旧连接——
        // 否则旧连接不在受控引用中，finishConnection 只能销毁最新一条，旧的双响铃连接永久残留
        activeConnection?.let { previous ->
            if (previous !== conn) {
                runCatching {
                    previous.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.CANCELED))
                    previous.destroy()
                }
                Log.i(TAG, "destroyed superseded connection callId=${previous.callId} duplicate=${previous.callId == callId}")
            }
        }
        activeConnection = conn
        return conn
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        connectionRequest: ConnectionRequest?,
    ): Connection? {
        // 发起呼叫走应用内 CallScreen，不需要 Telecom 托管
        return null
    }
}

/**
 * 自管 (self-managed) Connection：将系统通话事件路由回应用内呼叫流程。
 */
internal class MaodouchatConnection(
    private val applicationContext: android.content.Context,
    private val callerName: String,
    internal val callId: String,
    private val isVideo: Boolean,
) : Connection() {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var ringTimeoutFired = false
    @Volatile private var finished = false

    /** 8.49：自毁兜底——NavGraph 的 30s 振铃超时协程可能随 Composition/进程回收一起消失，
     *  此时 finishConnection 永不被调用，系统 Telecom 无限期 RINGING（幽灵响铃）。对齐
     *  AppNotifier.showIncomingCall 的 35s 通知超时，未接听即自毁并清 pending。 */
    private val ringTimeoutRunnable = Runnable {
        if (finished || ringTimeoutFired) return@Runnable
        ringTimeoutFired = true
        Log.w("MaodouConn", "ring timeout self-destruct callId=$callId")
        runCatching { IncomingCallCoordinator.clear() }
        if (callId.isNotBlank()) {
            runCatching { CallActionBus.requestHangUp(callId, notifyPeer = true) }
        }
        finish(DisconnectCause(DisconnectCause.CANCELED))
    }

    private fun finish(cause: DisconnectCause) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacks(ringTimeoutRunnable)
        runCatching {
            setDisconnected(cause)
            destroy()
        }
        MaodouchatConnectionService.clearActive(callId)
    }

    init {
        connectionCapabilities = PhoneAccount.CAPABILITY_SELF_MANAGED
        setConnectionProperties(PROPERTY_SELF_MANAGED)
        setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setAddress(Uri.parse("sip:maodouchat"), TelecomManager.PRESENTATION_ALLOWED)
        }
        statusHints = StatusHints(callerName, android.graphics.drawable.Icon.createWithResource(applicationContext, applicationContext.applicationInfo.icon), android.os.Bundle())
        setRinging()

        // 来电通知 / 系统通话界面点击 → 打开 MainActivity → NavGraph 路由到 IncomingCallRoute
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.maodouchat.INCOMING_CALL"
            putExtra(TelecomHelper.EXTRA_CALL_ID, callId)
            putExtra(TelecomHelper.EXTRA_CALLER_NAME, callerName)
            putExtra(TelecomHelper.EXTRA_IS_VIDEO, isVideo)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            callId.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            // setNotification 在部分 API 可见性受限，best-effort。
            val method = Connection::class.java.getMethod("setNotification", PendingIntent::class.java)
            method.invoke(this, pending)
        } catch (e: Exception) {
            // 部分 ROM / API 级别下 setNotification 不可见，忽略
        }
        // 8.49：35s 振铃自毁兜底（finish 会移除回调）
        mainHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS)
    }

    override fun onAnswer(videoState: Int) {
        Log.i("MaodouConn", "onAnswer: accepting callId=$callId videoState=$videoState")
        // 8.49 修复：接听前校验 pending 存活——超过 STALE_MS(120s) 后 pending 已被清空/过期，
        // 旧实现仍 startActivity 并 setActive，系统状态栏永久残留幽灵 ACTIVE 通话
        val pending = IncomingCallCoordinator.peekPending()
        val stalePending = pending == null ||
            (callId.isNotBlank() && pending.callId.isNotBlank() && pending.callId != callId)
        if (stalePending) {
            Log.w("MaodouConn", "onAnswer: pending stale/missing, dropping callId=$callId")
            finish(DisconnectCause(DisconnectCause.ERROR))
            return
        }
        // IncomingCallRoute 只认 pending.autoAnswer；来电页已打开时 poll alreadyHandled
        // 不会再带 autoAnswer 导航。必须 CAS 替换 pending 对象才能触发自动接听。
        val marked = IncomingCallCoordinator.markAutoAnswer(pending.callId.ifBlank { callId })
        if (!marked) {
            Log.w("MaodouConn", "onAnswer: markAutoAnswer failed, dropping callId=$callId")
            finish(DisconnectCause(DisconnectCause.ERROR))
            return
        }
        // 路由到应用内 CallScreen 完成实际接听
        try {
            val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = "com.maodouchat.ANSWER_CALL"
                putExtra(TelecomHelper.EXTRA_CALL_ID, callId)
                putExtra(TelecomHelper.EXTRA_CALLER_NAME, callerName)
                putExtra(TelecomHelper.EXTRA_IS_VIDEO, isVideo)
            }
            applicationContext.startActivity(openIntent)
            // BUG 2 fix: 仅在 startActivity 成功时 setActive，避免幽灵通话
            setActive()
            mainHandler.removeCallbacks(ringTimeoutRunnable)
        } catch (e: Exception) {
            Log.w("MaodouConn", "open CallScreen failed", e)
            finish(DisconnectCause(DisconnectCause.ERROR))
        }
    }

    override fun onAnswer() {
        onAnswer(0)
    }

    override fun onReject() {
        Log.i("MaodouConn", "onReject: rejecting callId=$callId")
        IncomingCallCoordinator.clear()
        if (callId.isNotBlank()) {
            CallActionBus.requestHangUp(callId, notifyPeer = true)
        }
        finish(DisconnectCause(DisconnectCause.REJECTED))
    }

    override fun onReject(reason: String?) {
        onReject()
    }

    override fun onDisconnect() {
        Log.i("MaodouConn", "onDisconnect: callId=$callId")
        IncomingCallCoordinator.clear()
        if (callId.isNotBlank()) {
            CallActionBus.requestHangUp(callId, notifyPeer = true)
        }
        finish(DisconnectCause(DisconnectCause.LOCAL))
    }

    override fun onAbort() {
        Log.i("MaodouConn", "onAbort: callId=$callId (remote cancel / timeout)")
        IncomingCallCoordinator.clear()
        finish(DisconnectCause(DisconnectCause.CANCELED))
    }

    private companion object {
        /** 略大于 NavGraph 的 30s 振铃超时与 AppNotifier 35s 通知超时对齐。 */
        private const val RING_TIMEOUT_MS = 35_000L
    }
}
