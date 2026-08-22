package com.maodouchat.push

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maodouchat.network.TokenManager

/**
 * 9.3xx：保活控制器——按用户选择的模式启停整条保活栈（Ideaura 五件套）。
 *
 * 生命周期钩子：
 * - 登录成功 / App 启动且已登录：ensureForUser()
 * - 登出 / 清除会话：stop()
 * - 设置页切换模式：applyMode()
 * - 周期任务（BacklogSyncWorker）内：ensureForUser() 自愈
 */
object PushKeepAlive {

    @Volatile
    internal var suppressResurrection = false

    fun ensureForUser(context: Context) {
        val mode = PushKeepAliveModeStore.mode(context)
        val hasToken = !TokenManager.getInstance(context).getToken().isNullOrBlank()
        if (!PushKeepAlivePolicy.shouldStartService(mode, hasToken)) return
        suppressResurrection = false
        val intent = Intent(context.applicationContext, PushKeepAliveService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }.onSuccess { Log.i(PushKeepAliveService.TAG, "keepalive started (mode=$mode)") }
            .onFailure { Log.w(PushKeepAliveService.TAG, "keepalive start failed (bg limit?): ${it.message}") }
    }

    /** 用户关闭保活或登出：清干净，禁止 daemon 互拉把 FGS 再拉起来。 */
    fun stop(context: Context) {
        suppressResurrection = true
        context.applicationContext.stopService(
            Intent(context.applicationContext, PushKeepAliveService::class.java)
        )
        FakeCallKeepAlive.removeFakeCall()
    }

    /** 模式切换：重启服务以应用新策略（媒体/假来电/仅前台）。 */
    fun applyMode(context: Context) {
        if (!PushKeepAliveModeStore.isEnabled(context)) {
            stop(context)
            return
        }
        ensureForUser(context)
    }

    /** 真实通话开始：挂断假来电让位，媒体模式暂停静音播放（避免音频焦点冲突）。 */
    fun onRealCallStarted(context: Context) {
        FakeCallKeepAlive.suspendForRealCall()
        MediaKeepAlive.stopActive()
    }

    /** 真实通话结束：重启服务按模式恢复保活策略（onStartCommand 内 applyStrategy）。 */
    fun onRealCallEnded(context: Context) {
        val mode = PushKeepAliveModeStore.mode(context)
        val hasToken = !TokenManager.getInstance(context).getToken().isNullOrBlank()
        if (PushKeepAlivePolicy.shouldStartService(mode, hasToken)) {
            ensureForUser(context)
        }
    }
}
