package com.maodouchat.push

import android.content.Context
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 9.3xx：Ideaura 同款「假来电保活」——注册一个独立的自管理 PhoneAccount，
 * 通过 addNewIncomingCall 挂起一个 onHold 状态的 Connection：
 * 系统把本进程当作通话应用处理（最高进程优先级），但不响铃、不弹通话 UI、
 * 不占用音频（setAudioModeIsVoip(false) + PROPERTY_SELF_MANAGED + onHold）。
 *
 * 与真实通话互斥：真实来电/去电开始时 [suspendForRealCall] 移除假来电，
 * 通话结束后 [resumeAfterRealCall] 按当前模式恢复（Maodouchat 自有通话走
 * MaodouchatConnectionService，假来电走 PushKeepAliveConnectionService，账号相互独立）。
 */
object FakeCallKeepAlive {
    private const val TAG = "FakeCallKeepAlive"
    private const val PHONE_ACCOUNT_ID = "maodouchat_push_keepalive"

    @Volatile
    private var fakeCallActive = false

    /** 当前挂起的假 Connection（用于干净地断开，而非 endCall() 一刀切误伤真实通话）。 */
    private val heldConnections = CopyOnWriteArrayList<Connection>()

    private fun accountHandle(context: Context): PhoneAccountHandle {
        val componentName = android.content.ComponentName(
            context.applicationContext,
            PushKeepAliveConnectionService::class.java
        )
        return PhoneAccountHandle(componentName, PHONE_ACCOUNT_ID)
    }

    /** 注册自管理账号（幂等，随保活服务启动调用一次）。 */
    fun registerPhoneAccount(context: Context) {
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        val handle = accountHandle(context)
        // getPhoneAccount 需要 MANAGE_OWN_CALLS appop（用户同意「管理自己的通话」后自动授予），
        // 直接尝试注册即可：重复注册是幂等的；系统会在首次注册时弹出同意提示
        val account = PhoneAccount.Builder(handle, "PushService")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        runCatching { telecomManager.registerPhoneAccount(account) }
            .onSuccess { Log.i(TAG, "keepalive phone account registered") }
            .onFailure { Log.w(TAG, "register phone account failed", it) }
    }

    /** 挂起假来电（Ideaura addNewIncomingCall 同款；onHold，不响铃不弹 UI）。 */
    fun addFakeCall(context: Context) {
        if (fakeCallActive) return
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        try {
            registerPhoneAccount(context)
            telecomManager.addNewIncomingCall(accountHandle(context), null)
            fakeCallActive = true
            Log.i(TAG, "fake call added")
        } catch (error: Exception) {
            Log.w(TAG, "add fake call failed", error)
        }
    }

    /** 移除假来电：只断开我们自己的 Connection，不动真实通话。 */
    fun removeFakeCall() {
        fakeCallActive = false
        val connections = heldConnections.toList()
        heldConnections.clear()
        connections.forEach { connection ->
            runCatching {
                if (connection.state == Connection.STATE_HOLDING ||
                    connection.state == Connection.STATE_ACTIVE ||
                    connection.state == Connection.STATE_INITIALIZING ||
                    connection.state == Connection.STATE_DIALING ||
                    connection.state == Connection.STATE_RINGING
                ) {
                    connection.setDisconnected(
                        android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL)
                    )
                }
                connection.destroy()
            }.onFailure { Log.w(TAG, "disconnect fake connection failed", it) }
        }
        Log.i(TAG, "fake call removed")
    }

    /** 由 PushKeepAliveConnectionService 在创建 Connection 时登记，便于干净拆除。 */
    fun trackConnection(connection: Connection) {
        heldConnections += connection
    }

    /** 连接被系统挂断（超时/注销）时回调：清空登记，保活服务周期性重挂。 */
    fun notifyDisconnected(connection: Connection) {
        heldConnections.remove(connection)
        if (heldConnections.isEmpty()) fakeCallActive = false
    }

    fun isActive(): Boolean = fakeCallActive

    /** 真实通话开始：让位（挂断假来电，避免占线/账号冲突）。 */
    fun suspendForRealCall() {
        if (!fakeCallActive) return
        Log.i(TAG, "real call started, suspending fake call")
        removeFakeCall()
    }

    /** 真实通话结束：按当前保活模式恢复假来电。 */
    fun resumeAfterRealCall(context: Context) {
        if (PushKeepAliveModeStore.wantsFakeCall(context)) {
            Log.i(TAG, "real call ended, resuming fake call")
            addFakeCall(context)
        }
    }
}
