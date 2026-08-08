package com.maodouchat.security

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * 拨号盘恢复入口：隐藏桌面图标后，在拨号盘输入 `*#*#75263#*#*` 恢复图标并拉起 App。
 *
 * 系统拨号器对 `android_secret_code` scheme 发起隐式广播（数据 host 必须与 manifest
 * 中声明一致，即 [FakeChatManager.SECRET_CODE]）。host 不匹配时直接忽略，不影响其他应用。
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = intent.data?.host ?: return
        if (host != FakeChatManager.SECRET_CODE) return
        val pm = context.packageManager
        val component = ComponentName(context, com.maodouchat.MainActivity::class.java)
        runCatching {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        runCatching {
            context.startActivity(
                Intent(context, com.maodouchat.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // 广播接收器里不能直接弹 Toast 上下文，延迟用 applicationContext 兜底即可
        runCatching {
            Toast.makeText(
                context.applicationContext,
                "已恢复桌面入口",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
