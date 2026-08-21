package com.maodouchat.push

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

/**
 * 9.3xx：保活专用的自管理 ConnectionService（Ideaura IdeauraConnectionService 同款）。
 * 与真实通话的 MaodouchatConnectionService 完全独立（不同 PhoneAccount）：
 * 假来电永远返回 onHold 的 Connection —— 不响铃、不弹通话 UI、不占音频。
 * 系统只能通过 BIND_TELECOM_CONNECTION_SERVICE 权限绑定（manifest 声明）。
 */
class PushKeepAliveConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        phoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection = createHeldConnection()

    override fun onCreateOutgoingConnection(
        phoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection = createHeldConnection()

    private fun createHeldConnection(): Connection {
        return object : Connection() {
            init {
                // 自管理连接必须先 setActive 再 setOnHold，否则一直停在 RINGING：
                // 铃不响但系统按"来电中"压制消息通知/音效，且 ~60s 后被系统按未接来电挂断。
                // init 里系统尚未完成附加，post 到主循环再 active；ACTIVE 确认后在
                // onStateChanged 里再 hold（立即连续调用 setOnHold 会被异步状态机吞掉）。
                setConnectionCapabilities(
                    Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD
                )
                setAudioModeIsVoip(false)
                setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { setActive() }
                    Log.d("PushKeepAlive", "held fake connection attached (active)")
                }
                FakeCallKeepAlive.trackConnection(this)
            }

            override fun onStateChanged(state: Int) {
                super.onStateChanged(state)
                when (state) {
                    Connection.STATE_ACTIVE -> {
                        // 激活后立即转保持：通话级优先级，但无铃声、无通话 UI、不占音频。
                        // 附加完成后再设一次能力位，避免 init 里的设置被框架重置导致 hold 被拒。
                        setConnectionCapabilities(
                            Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD
                        )
                        runCatching { setOnHold() }
                        Log.d("PushKeepAlive", "held fake connection on hold")
                    }
                    Connection.STATE_DISCONNECTED -> {
                        Log.d("PushKeepAlive", "fake connection disconnected by system")
                        FakeCallKeepAlive.notifyDisconnected(this)
                    }
                }
            }
        }
    }
}
