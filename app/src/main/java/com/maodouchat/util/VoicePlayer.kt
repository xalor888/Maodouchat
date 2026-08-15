package com.maodouchat.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全局单例语音播放器：
 *  - 同一时刻只播一条
 *  - 自动释放 MediaPlayer
 *  - 暴露当前播放状态（messageId + 进度 + 倍速 + 听筒/扬声器）给 UI 订阅
 *  - 进度轮询用协程，避免裸 Thread 的中断/释放竞态
 */
object VoicePlayer {
    private var player: MediaPlayer? = null
    private var currentId: String? = null
    /** 1.05：最近自然播放完成的消息 id（UI 据此播放下一条语音；手动停止不更新）。 */
    @Volatile
    var lastCompletedId: String? = null
    private var currentSource: String = ""
    private var resumeAfterFocusLoss: Boolean = false
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var routeApplied: Boolean = false

    private val progressScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    // 1.135：倍速偏好跨进程持久化
    private const val PREFS_NAME = "voice_player_settings"
    private const val KEY_SPEED = "speed"
    private var speedLoaded = false

    private fun loadSavedSpeed() {
        if (speedLoaded) return
        speedLoaded = true
        val ctx = appContext ?: return
        val saved = runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1f)
        }.getOrDefault(1f)
        val normalized = SPEED_STEPS.firstOrNull { it == saved } ?: 1f
        _state.value = _state.value.copy(speed = normalized)
    }

    private fun persistSpeed(speed: Float) {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, speed).apply()
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val messageId: String? = null,
        val isPlaying: Boolean = false,
        val progress: Float = 0f,
        val durationMs: Long = 0L,
        /** 0.5 / 1.0 / 1.5 / 2.0 */
        val speed: Float = 1f,
        /** true = 听筒，false = 扬声器（媒体） */
        val earpiece: Boolean = false,
    )

    /** 允许的播放倍速（循环切换）。 */
    val SPEED_STEPS: FloatArray = floatArrayOf(1f, 1.5f, 2f, 0.5f)

    fun ensureContext(context: Context) {
        if (appContext == null) {
            val app = context.applicationContext
            appContext = app
            audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            loadSavedSpeed()
        }
    }

    fun play(messageId: String, source: String, context: Context? = null) {
        context?.let { ensureContext(it) }
        stopInternal(keepRoutePreference = true)
        // BUG 1 fix: 请求 AudioFocus，使背景音乐自动降低音量
        requestAudioFocus()
        try {
            val earpiece = _state.value.earpiece
            val speed = _state.value.speed.let { if (it in SPEED_STEPS.toList()) it else 1f }
            val mp = MediaPlayer()
            player = mp
            currentId = messageId
            currentSource = source
            mp.apply {
                setAudioAttributes(buildAttributes(earpiece))
                when {
                    source.startsWith("content://") -> {
                        // content:// URI 必须用 setDataSource(Context, Uri) 重载，
                        // 否则部分 ROM/API 级别上会因 ContentProvider 权限解析失败而抛 IOException
                        val uri = android.net.Uri.parse(source)
                        val ctx = appContext
                            ?: throw IllegalStateException("VoicePlayer.ensureContext not called")
                        setDataSource(ctx, uri)
                    }
                    source.startsWith("file://") ->
                        setDataSource(source.removePrefix("file://"))
                    else -> setDataSource(source)
                }
                setOnPreparedListener { p ->
                    if (player !== mp) { runCatching { p.release() }; return@setOnPreparedListener }
                    val duration = p.duration.toLong().coerceAtLeast(0L)
                    applyPlaybackSpeed(p, speed)
                    applyAudioRoute(earpiece)
                    _state.value = State(
                        messageId = messageId,
                        isPlaying = true,
                        progress = 0f,
                        durationMs = duration,
                        speed = speed,
                        earpiece = earpiece,
                    )
                    p.start()
                    startProgressLoop(p, duration)
                    // 1.237：开始播放即标记已播放（红点立即消失，Telegram 式）
                    currentId?.let { id -> appContext?.let { ctx -> runCatching { com.maodouchat.util.VoicePlayedStore.markPlayed(ctx, id) } } }
                }
                setOnCompletionListener {
                    if (player !== mp) return@setOnCompletionListener
                    // 1.05：记录自然完成的消息 id（供 UI 连续播放下一条）
                    lastCompletedId = currentId
                    // 1.176：自然播放完成 → 标记已播放（气泡未读红点消失）
                    currentId?.let { id -> appContext?.let { ctx -> runCatching { com.maodouchat.util.VoicePlayedStore.markPlayed(ctx, id) } } }
                    // 播放自然结束：彻底还原系统音频路由（mode/speakerphone），避免影响后续系统/其他 App 音频
                    stopInternal(keepRoutePreference = false)
                }
                setOnErrorListener { _, _, _ ->
                    if (player !== mp) { runCatching { mp.release() }; return@setOnErrorListener true }
                    stopInternal(keepRoutePreference = false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.w("VoicePlayer", "play failed for $messageId", e)
            stopInternal(keepRoutePreference = true)
        }
    }

    fun togglePlayPause(messageId: String, source: String, context: Context? = null) {
        context?.let { ensureContext(it) }
        val mp = player
        if (currentId == messageId && mp != null) {
            // 8.33 修复：pause/start 无防护——MediaPlayer 进入 Error 态（源文件被缓存清理删除、
            // 解码失败）时抛 IllegalStateException 直达主线程崩溃；与 play() 一致包 runCatching。
            val playing = runCatching { mp.isPlaying }.getOrElse {
                stopInternal(keepRoutePreference = true)
                return
            }
            if (playing) {
                try {
                    mp.pause()
                } catch (_: IllegalStateException) {
                    stopInternal(keepRoutePreference = true)
                    return
                }
                _state.value = _state.value.copy(isPlaying = false)
                progressJob?.cancel()
            } else {
                try {
                    applyAudioRoute(_state.value.earpiece)
                    mp.start()
                } catch (_: IllegalStateException) {
                    stopInternal(keepRoutePreference = true)
                    return
                }
                _state.value = _state.value.copy(isPlaying = true)
                startProgressLoop(mp, _state.value.durationMs)
            }
        } else {
            play(messageId, source, context)
        }
    }

    /** 在 1x → 1.5x → 2x → 0.5x 间循环；对当前 MediaPlayer 立即生效。 */
    fun cycleSpeed() {
        val steps = SPEED_STEPS
        val current = _state.value.speed
        val idx = steps.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        val next = steps[(idx + 1) % steps.size]
        setSpeed(next)
    }

    /**
     * 8.44：拖动进度跳转。仅对当前选中消息生效；暂停态也允许 seek（只更新进度，
     * 不自动播放）。MediaPlayer 在 Error/未预备态抛 IllegalStateException，runCatching 防护。
     */
    fun seekTo(messageId: String, progressMs: Long) {
        val mp = player ?: return
        if (currentId != messageId) return
        val target = progressMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        if (runCatching { mp.seekTo(target.toInt()) }.isFailure) return
        _state.value = _state.value.copy(progress = if (_state.value.durationMs > 0L) {
            (target.toFloat() / _state.value.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        })
    }

    fun setSpeed(speed: Float) {
        val normalized = SPEED_STEPS.firstOrNull { it == speed } ?: 1f
        _state.value = _state.value.copy(speed = normalized)
        // 1.135：倍速选择跨进程持久化（重启后仍记住用户偏好）
        persistSpeed(normalized)
        player?.let { applyPlaybackSpeed(it, normalized) }
    }

    /**
     * 切换听筒 / 扬声器。
     * 听筒使用语音通话流；扬声器使用媒体流。切换时尽量保持进度。
     */
    fun toggleEarpiece(context: Context? = null) {
        context?.let { ensureContext(it) }
        setEarpiece(!_state.value.earpiece)
    }

    fun setEarpiece(earpiece: Boolean) {
        if (_state.value.earpiece == earpiece && routeApplied) {
            // 仍同步一次路由，防止外部改动 mode
            applyAudioRoute(earpiece)
            return
        }
        _state.value = _state.value.copy(earpiece = earpiece)
        val mp = player
        if (mp != null) {
            // 属性在 prepare 后不可改，靠 AudioManager 路由 + 重新 setAudioAttributes 不可靠；
            // 使用 mode + speakerphone 切换输出。
            applyAudioRoute(earpiece)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 部分机型需重新绑定属性；失败则仅依赖 AudioManager
                    runCatching {
                        mp.setAudioAttributes(buildAttributes(earpiece))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        stopInternal(keepRoutePreference = false)
    }

    private fun stopInternal(keepRoutePreference: Boolean) {
        progressJob?.cancel()
        progressJob = null
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
        currentId = null
        currentSource = ""
        resumeAfterFocusLoss = false
        abandonAudioFocus()
        // 保留路由偏好（如 play() 开头为避免混音先 stop 上一段）时不复位系统音频路由，
        // 否则用户已选的听筒/扬声器设置会被还原成默认。
        if (!keepRoutePreference) restoreAudioRoute()
        val prev = _state.value
        _state.value = State(speed = prev.speed, earpiece = prev.earpiece)
    }

    private fun buildAttributes(earpiece: Boolean): AudioAttributes {
        return if (earpiece) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }
    }

    private fun applyPlaybackSpeed(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
            // 部分机型 setPlaybackParams 会暂停
            if (wasPlaying && !mp.isPlaying) {
                runCatching { mp.start() }
            }
        } catch (e: Exception) {
            android.util.Log.w("VoicePlayer", "setSpeed $speed failed", e)
        }
    }

    /** BUG 1 fix: 请求瞬时独占 AudioFocus，使背景音乐暂停或降低音量。 */
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            // 8.33 修复：焦点丢失（来电/其他 App 抢占）时暂停并记录待恢复，恢复后自动续播；
            // 此前空实现导致语音在他人通话中继续外放，且已把系统切到 MODE_IN_COMMUNICATION
            .setOnAudioFocusChangeListener { change ->
                val playingId = currentId
                when (change) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // 9.142：焦点回调异步到达，期间用户可能已切换到其它语音——仅当快照与
                        // 当前播放一致时才暂停；绝不走 togglePlayPause 的 else=play 分支把旧语音切回
                        resumeAfterFocusLoss = _state.value.isPlaying
                        val mp = player
                        if (_state.value.isPlaying && playingId != null && playingId == currentId && mp != null) {
                            runCatching {
                                if (mp.isPlaying) {
                                    mp.pause()
                                    _state.value = _state.value.copy(isPlaying = false)
                                    progressJob?.cancel()
                                }
                            }
                        }
                    }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        // 9.142：恢复同样校验快照一致——用户已切走时不得把旧语音拉起
                        val mp = player
                        if (resumeAfterFocusLoss && !_state.value.isPlaying && playingId != null &&
                            playingId == currentId && mp != null
                        ) {
                            resumeAfterFocusLoss = false
                            runCatching {
                                if (!mp.isPlaying) {
                                    mp.start()
                                    _state.value = _state.value.copy(isPlaying = true)
                                    startProgressLoop(mp, _state.value.durationMs)
                                }
                            }
                        }
                    }
                }
            }
            .build()
        am.requestAudioFocus(request)
        audioFocusRequest = request
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let {
            am.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun applyAudioRoute(earpiece: Boolean) {
        val am = audioManager ?: return
        if (!routeApplied) {
            savedAudioMode = am.mode
            @Suppress("DEPRECATION")
            savedSpeakerphone = am.isSpeakerphoneOn
            routeApplied = true
        }
        try {
            if (earpiece) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            } else {
                am.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            android.util.Log.w("VoicePlayer", "applyAudioRoute failed", e)
        }
    }

    private fun restoreAudioRoute() {
        if (!routeApplied) return
        val am = audioManager
        if (am != null) {
            try {
                am.mode = savedAudioMode
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = savedSpeakerphone
            } catch (_: Exception) {
            }
        }
        routeApplied = false
    }

    private fun startProgressLoop(mp: MediaPlayer, duration: Long) {
        progressJob?.cancel()
        progressJob = progressScope.launch {
            while (isActive) {
                val pos = try {
                    mp.currentPosition
                } catch (_: Exception) {
                    -1
                }
                if (pos < 0 || duration <= 0) break
                val progress = pos.toFloat() / duration
                _state.value = _state.value.copy(
                    progress = progress.coerceIn(0f, 1f),
                    durationMs = duration,
                )
                delay(50)
            }
        }
    }

    /** 下一档倍速（纯函数，供 UI/单测）。 */
    fun nextSpeed(current: Float): Float {
        val steps = SPEED_STEPS
        val idx = steps.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        return steps[(idx + 1) % steps.size]
    }

    fun formatSpeedLabel(speed: Float): String {
        return when (speed) {
            0.5f -> "0.5x"
            1.5f -> "1.5x"
            2f -> "2x"
            else -> "1x"
        }
    }
}
