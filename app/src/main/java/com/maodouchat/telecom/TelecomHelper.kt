package com.maodouchat.telecom

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * 注册 PhoneAccount 并触发系统级来电。
 *
 * 通过 [TelecomManager.addNewIncomingCall] 让 Android Telecom 框架接管来电 UI，
 * 在锁屏 / 后台场景下展示系统原生通话界面（含接听 / 拒接 / 静音），
 * 接听后路由回应用内 [com.maodouchat.ui.screen.call.CallScreen]。
 */
object TelecomHelper {
    private const val TAG = "TelecomHelper"
    const val PHONE_ACCOUNT_ID = "maodouchat_calls"

    const val EXTRA_CALLER_NAME = "maodouchat.caller_name"
    const val EXTRA_CALL_ID = "maodouchat.call_id"
    const val EXTRA_IS_VIDEO = "maodouchat.is_video"

    @Volatile
    private var registeredHandle: PhoneAccountHandle? = null

    fun handle(context: Context): PhoneAccountHandle {
        registeredHandle?.let { return it }
        val cn = ComponentName(context, MaodouchatConnectionService::class.java)
        val handle = PhoneAccountHandle(cn, PHONE_ACCOUNT_ID)
        registeredHandle = handle
        return handle
    }

    /**
     * 注册（或刷新）应用自有 PhoneAccount。
     * 仅需调用一次；重复调用会覆盖旧 account。
     */
    fun registerPhoneAccount(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val account = PhoneAccount.Builder(
                handle(context),
                "毛豆聊天通话"
            )
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .setIcon(android.graphics.drawable.Icon.createWithResource(context.packageName, context.applicationInfo.icon))
                .build()
            tm.registerPhoneAccount(account)
            Log.i(TAG, "PhoneAccount registered")
        } catch (e: SecurityException) {
            Log.w(TAG, "PhoneAccount registration denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "PhoneAccount registration failed", e)
        }
    }

    fun isRegistered(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.getPhoneAccount(handle(context)) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 触发系统来电。调用方应在 [IncomingCallCoordinator.setPending] 之后立即调用。
     *
     * @return true 表示已成功派发；false 表示 Telecom 不可用或未注册（应用应回退到应用内 UI）
     */
    fun placeIncomingCall(
        context: Context,
        callerName: String,
        callId: String,
        isVideo: Boolean,
    ): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle().apply {
                putString(EXTRA_CALLER_NAME, callerName)
                putString(EXTRA_CALL_ID, callId)
                putBoolean(EXTRA_IS_VIDEO, isVideo)
            }
            tm.addNewIncomingCall(handle(context), extras)
            Log.i(TAG, "Incoming call dispatched via Telecom: $callerName")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "addNewIncomingCall denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "addNewIncomingCall failed", e)
            false
        }
    }

    /**
     * 通知 Telecom 框架一个已派发的来电已被消费（接听 / 拒接 / 超时）。
     * 由 ConnectionService 内部调用，外部一般不需要。
     */
    fun cancelIncomingCall(context: Context) {
        // 自管 (self-managed) 模式下无需显式 cancel；Connection.destroy() 即可。
    }
}
