package com.maodouchat.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 9.3xx：保活守护服务（Ideaura RemoteDaemonService 同款）——独立进程 `:pushdaemon`，
 * 主进程保活服务被系统销毁时由它重新拉起；保活服务自身被销毁时也会启动本服务，
 * 形成互相复活。拉起受 Android 12+ 后台启动限制时静默失败，由 START_STICKY /
 * 周期任务（BacklogSyncWorker 内 ensureForUser）兜底。
 */
class PushDaemonService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasToken = !com.maodouchat.network.TokenManager.getInstance(this).getToken().isNullOrBlank()
        if (
            PushKeepAlive.suppressResurrection ||
            !PushKeepAlivePolicy.shouldStartService(PushKeepAliveModeStore.mode(this), hasToken)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(PushKeepAliveService.TAG, "daemon restarting keepalive service")
        runCatching {
            val keepAliveIntent = Intent(this, PushKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keepAliveIntent)
            } else {
                startService(keepAliveIntent)
            }
        }.onFailure {
            Log.w(PushKeepAliveService.TAG, "daemon could not restart keepalive (bg start limit): ${it.message}")
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PushDaemonService::class.java)) }
        }
    }
}
