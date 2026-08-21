package com.maodouchat.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 9.3xx：开机/应用更新后按设置恢复保活（登录态由 PushKeepAlive.ensureForUser 自行校验）。
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED 均属于系统允许启动前台服务的豁免场景。
 */
class PushKeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(PushKeepAliveService.TAG, "boot/update: restoring keepalive if enabled")
                PushKeepAlive.ensureForUser(context)
            }
        }
    }
}
