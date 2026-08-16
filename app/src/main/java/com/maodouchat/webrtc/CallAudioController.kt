package com.maodouchat.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class CallAudioRoute { BLUETOOTH, WIRED, EARPIECE, SPEAKER }

object CallAudioRoutePolicy {
    fun preferred(available: Set<CallAudioRoute>, speakerPreferred: Boolean): CallAudioRoute =
        candidates(available, speakerPreferred, manualRoute = null).first()

    fun selected(
        available: Set<CallAudioRoute>,
        speakerPreferred: Boolean,
        manualRoute: CallAudioRoute?
    ): CallAudioRoute = candidates(available, speakerPreferred, manualRoute).first()

    fun candidates(
        available: Set<CallAudioRoute>,
        speakerPreferred: Boolean,
        manualRoute: CallAudioRoute?
    ): List<CallAudioRoute> = buildList {
        manualRoute?.takeIf { it in available }?.let(::add)
        listOfNotNull(
            CallAudioRoute.BLUETOOTH.takeIf { it in available },
            CallAudioRoute.WIRED.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { speakerPreferred && it in available },
            CallAudioRoute.EARPIECE.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { it in available }
        ).forEach { route -> if (route !in this) add(route) }
        available.forEach { route -> if (route !in this) add(route) }
        if (isEmpty()) add(CallAudioRoute.SPEAKER)
    }
}

/** Owns audio focus and communication routing for the lifetime of one WebRTC manager. */
class CallAudioController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusRequested = false
    private var deviceCallbackRegistered = false
    private var audioModeChanged = false
    private var routingChanged = false
    @Volatile private var started = false
    private var speakerPreferred = false
    private var manualRoute: CallAudioRoute? = null
    private var currentRoute: CallAudioRoute? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var routeListener: ((Set<CallAudioRoute>, CallAudioRoute) -> Unit)? = null
    @Volatile private var focusListener: ((Boolean) -> Unit)? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (started) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> notifyFocusChanged(true)
                // 8.49 修复：仅永久丢失才上报失焦——TRANSIENT（来电/助手瞬间遮挡）与
                // CAN_DUCK（通知提示音压低音量）语义都不该禁用麦克风发送轨，
                // 旧实现把三类等同处理，一次提示音就会让对端瞬间听不到本端
                AudioManager.AUDIOFOCUS_LOSS -> notifyFocusChanged(false)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            }
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshRouteFromDeviceCallback()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshRouteFromDeviceCallback()
        }
    }

    @Synchronized
    fun setOnRoutesChangedListener(listener: ((Set<CallAudioRoute>, CallAudioRoute) -> Unit)?) {
        routeListener = listener
        if (started && listener != null) runCatching { refreshRoute() }
    }

    @Synchronized
    fun setOnAudioFocusChangedListener(listener: ((Boolean) -> Unit)?) {
        focusListener = listener
    }

    @Synchronized
    fun start(callType: CallType): CallAudioRoute {
        speakerPreferred = callType != CallType.AUDIO
        if (started) return refreshRoute()

        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        try {
            val focusGranted = requestFocus()
            notifyFocusChanged(focusGranted)
            check(focusGranted) { "audio focus unavailable" }

            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            deviceCallbackRegistered = true
            audioModeChanged = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            started = true
            return refreshRoute()
        } catch (error: Throwable) {
            cleanupAudioSession()
            throw error
        }
    }

    @Synchronized
    fun selectRoute(route: CallAudioRoute): Boolean {
        if (!started) return false
        val (available, devices) = availableRoutesAndDevices()
        if (route !in available) return false
        if (!applyRoute(route, devices)) return false
        manualRoute = route
        currentRoute = route
        notifyRoutesChanged(available, route)
        return true
    }

    @Synchronized
    fun stop() {
        cleanupAudioSession()
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest = request
            focusRequested = true
            audioManager.requestAudioFocus(request)
        } else {
            focusRequested = true
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun refreshRoute(): CallAudioRoute {
        val (available, mapped) = availableRoutesAndDevices()
        if (manualRoute != null && manualRoute !in available) manualRoute = null
        val selected = CallAudioRoutePolicy.candidates(available, speakerPreferred, manualRoute)
            .firstOrNull { route -> applyRoute(route, mapped) }
            ?: throw IllegalStateException("no available audio route could be applied")
        currentRoute = selected
        notifyRoutesChanged(available, selected)
        return selected
    }

    private fun applyRoute(preferred: CallAudioRoute, mapped: Map<CallAudioRoute, AudioDeviceInfo>): Boolean {
        routingChanged = true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = mapped[preferred]
                // 8.46 修复：Android 12+ setCommunicationDevice 成功即返回 true——
                // 此前对所有非 SPEAKER 路由（蓝牙/有线/听筒）固定返回 false，refreshRoute
                // 用 firstOrNull{applyRoute} 迭代候选时全部"失败"→ 通话永远只能外放。
                if (device != null && canUseCommunicationDevice(device)) {
                    // 8.49 修复：透传 setCommunicationDevice 的真实结果——失败时落入
                    // firstOrNull 的下一候选；旧实现无条件 true，蓝牙正断开等瞬时报错
                    // 会让选路短路"成功"，UI 显示已切换但实际输出设备未变
                    audioManager.setCommunicationDevice(device)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = preferred == CallAudioRoute.SPEAKER
                    preferred == CallAudioRoute.SPEAKER
                }
            } else {
                if (preferred != CallAudioRoute.BLUETOOTH) {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                }
                when (preferred) {
                    CallAudioRoute.BLUETOOTH -> {
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        true
                    }
                    CallAudioRoute.SPEAKER -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        true
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    private fun availableRoutesAndDevices(): Pair<Set<CallAudioRoute>, Map<CallAudioRoute, AudioDeviceInfo>> {
        val mapped = outputDevices()
            .mapNotNull { device ->
                val route = routeForDevice(device) ?: return@mapNotNull null
                if (route == CallAudioRoute.BLUETOOTH && !canUseCommunicationDevice(device)) return@mapNotNull null
                route to device
            }
            .toMap()
        val available = mapped.keys.ifEmpty { setOf(CallAudioRoute.SPEAKER) }
        return available to mapped
    }

    private fun outputDevices(): List<AudioDeviceInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.availableCommunicationDevices
        else audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }.getOrDefault(emptyList())

    private fun routeForDevice(device: AudioDeviceInfo): CallAudioRoute? = when {
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> CallAudioRoute.BLUETOOTH
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> CallAudioRoute.BLUETOOTH
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> CallAudioRoute.WIRED
        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioRoute.EARPIECE
        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioRoute.SPEAKER
        else -> null
    }

    private fun canUseCommunicationDevice(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || routeForDevice(device) != CallAudioRoute.BLUETOOTH) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshRouteFromDeviceCallback() {
        if (!started) return
        synchronized(this) {
            if (started) runCatching { refreshRoute() }
        }
    }

    private fun notifyRoutesChanged(available: Set<CallAudioRoute>, selected: CallAudioRoute) {
        runCatching { routeListener?.invoke(available, selected) }
    }

    private fun notifyFocusChanged(granted: Boolean) {
        runCatching { focusListener?.invoke(granted) }
    }

    private fun cleanupAudioSession() {
        val ownsSession = started || deviceCallbackRegistered || focusRequested || audioModeChanged || routingChanged
        started = false
        if (!ownsSession) {
            previousAudioMode = null
            previousSpeakerphoneOn = null
            return
        }
        if (deviceCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            deviceCallbackRegistered = false
        }
        if (routingChanged) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            } else {
                runCatching {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                }
            }
            previousSpeakerphoneOn?.let { previous -> runCatching {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previous
            } }
        }
        if (audioModeChanged) {
            previousAudioMode?.let { previous -> runCatching { audioManager.mode = previous } }
        }

        if (focusRequested) {
            val request = focusRequest
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                runCatching { audioManager.abandonAudioFocusRequest(request) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { audioManager.abandonAudioFocus(audioFocusChangeListener) }
            }
        }
        focusRequest = null
        focusRequested = false
        audioModeChanged = false
        routingChanged = false
        previousAudioMode = null
        previousSpeakerphoneOn = null
        manualRoute = null
        currentRoute = null
    }
}
