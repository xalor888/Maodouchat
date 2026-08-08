package com.maodouchat.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.maodouchat.util.SecretSimChangePrefs

/**
 * 密聊 SIM 变更防护（B2 surface · SIM 变更防护，health 名 simz）。
 *
 * 周期性 / 按需核对当前 SIM 标识（优先 subscriber id，其次 sim serial），
 * 与 [SecretSimChangePrefs] 中记录的上次 SIM 比对：
 * - 首次记录：登记基线；
 * - 之后若 SIM 消失或更换：回调 [onSimChanged]，由接入方锁定并清除密聊会话
 *   （典型接入：`SecretChatSession.clearAllSurfaces(context)` + 提示用户重新验证）。
 *
 * 权限说明：部分字段需 READ_PHONE_STATE，缺失时退回「不启用」安全默认，
 * 避免静默降级造成假安全感。仅本机生效，服务端不接触密聊明文。
 */
class SimChangeWatcher(
    private val context: Context,
    private val onSimChanged: () -> Unit = {}
) {
    @Volatile private var lastObservedSimId: String? = null

    /** 无权限时返回 null（视为无法观察，调用方应保守处理）。 */
    fun currentSimId(): String? {
        if (!SecretSimChangePrefs.isEnabled(context)) return null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val ids = mutableListOf<String>()
        runCatching { tm.subscriberId?.takeIf { it.isNotBlank() }?.let { ids += "sub:$it" } }
        runCatching { tm.simSerialNumber?.takeIf { it.isNotBlank() }?.let { ids += "sim:$it" } }
        runCatching {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (sm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                sm.activeSubscriptionInfoList.orEmpty()
                    .filterIsInstance<SubscriptionInfo>()
                    .forEach { info ->
                        info.subscriptionId.takeIf { it > 0 }?.let { ids += "subId:$it" }
                    }
            }
        }
        return ids.sorted().joinToString("|").takeIf { it.isNotBlank() }
    }

    /**
     * 核对一次。返回是否发生变更（true = SIM 被拔出/更换）。
     * 首次调用只登记基线；之后每次比对 [SecretSimChangePrefs.lastSimId]。
     */
    fun checkNow(): Boolean {
        if (!SecretSimChangePrefs.isEnabled(context)) return false
        val current = currentSimId()
        val previous = SecretSimChangePrefs.lastSimId(context)
        if (current == null) {
            // 8.40：SIM 被拔出时 subscriberId/simSerial/subscriptionId 全为空 → currentSimId() 返回 null。
            // 此前直接 return false 使「SIM 消失」分支永不生效（文档承诺的「消失或更换」只剩更换）；
            // 有已登记基线时视为「SIM 已移除」并清除，插回后重新登记新基线。
            if (!previous.isNullOrBlank()) {
                Log.w(TAG, "SIM removed (no readable SIM); secret session protection triggered")
                SecretSimChangePrefs.setLastSimId(context, "")
                lastObservedSimId = null
                onSimChanged()
                return true
            }
            return false
        }
        if (previous.isNullOrBlank()) {
            SecretSimChangePrefs.setLastSimId(context, current)
            lastObservedSimId = current
            return false
        }
        if (current == previous) {
            lastObservedSimId = current
            return false
        }
        // 变更：登记新基线并回调，由接入方决定锁/焚行为
        SecretSimChangePrefs.setLastSimId(context, current)
        lastObservedSimId = current
        Log.w(TAG, "SIM changed; secret session protection triggered")
        onSimChanged()
        return true
    }

    /** 供接入方在每次启动/回前台时调用。 */
    fun onForeground() {
        checkNow()
    }

    companion object {
        private const val TAG = "SimChangeWatcher"
    }
}
