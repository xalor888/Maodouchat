package com.maodouchat.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import com.maodouchat.domain.messaging.VoiceCaptureAmplitude

/**
 * 语音录制：AAC/M4A + 振幅采样（供录音波形 UI）。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0

    /** 是否正在录制 */
    var isRecording: Boolean = false
        private set

    /**
     * 开始录制
     */
    fun startRecording() {
        // 重入保护：已在录制时直接忽略，避免新建第二个 MediaRecorder 抢占 Mic 造成资源泄漏/录制异常
        if (isRecording) {
            Log.w(TAG, "startRecording ignored: already recording")
            return
        }
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file

        var newRecorder: MediaRecorder? = null
        try {
            newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = newRecorder
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            startTime = System.currentTimeMillis()
            isRecording = true
        } catch (e: Exception) {
            Log.w(TAG, "startRecording failed (file=${file.absolutePath})", e)
            runCatching { newRecorder?.release() }
            recorder = null
            isRecording = false
            outputFile = null
            file.delete()
            throw e
        }
    }

    /**
     * 归一化振幅 0..1（未在录制时为 0）。
     * MediaRecorder maxAmplitude 为瞬时峰值，对数压缩后便于波形展示。
     */
    fun amplitude(): Float {
        if (!isRecording) return 0f
        val raw = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
        return VoiceCaptureAmplitude.normalizeAmplitude(raw)
    }

    /** 当前已录时长毫秒；未录制返回 0。 */
    fun elapsedMs(): Long {
        if (!isRecording || startTime <= 0L) return 0L
        return (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
    }

    /**
     * 停止录制并返回录音文件路径
     *
     * @return Pair(文件路径, 时长毫秒)
     */
    fun stopRecording(): Pair<String, Long>? {
        if (!isRecording) return null
        val file = outputFile ?: return null

        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false
            outputFile = null

            val duration = System.currentTimeMillis() - startTime
            Pair(file.absolutePath, duration)
        } catch (e: Exception) {
            // 录制过短或 stop 失败时 MediaRecorder 会抛异常 — 必须 Log 以区分"用户没录"和"录制失败"
            Log.w(TAG, "stopRecording failed (file=${file.absolutePath})", e)
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
            file.delete()
            outputFile = null
            null
        }
    }

    /**
     * 取消录制
     */
    fun cancelRecording() {
        if (!isRecording && recorder == null && outputFile == null) return
        val file = outputFile
        try {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        } catch (e: Exception) {
            // release 失败意味着 mic 资源可能仍被占用 — 必须 Log 以便排查
            Log.w(TAG, "cancelRecording release failed", e)
        } finally {
            recorder = null
            isRecording = false
            outputFile = null
            file?.delete()
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
