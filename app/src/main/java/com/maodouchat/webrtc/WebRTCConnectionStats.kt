package com.maodouchat.webrtc

import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接统计采样簇（G390 从 WebRTCManager 抽出）。
 *
 * 采样状态（lastStatsSnapshot / 请求去重 / 世代号）全收进 [WebRTCConnectionStatsCollector]；
 * 原来的 `released`/`peerConnection`/`peerConnections` 成员访问改走构造注入的三个
 * lambda（释放检查、直连链路、群聊链路），调用语义逐字不变。
 *
 * 当前主链路的连接统计快照。用于驱动 UI 上的"网络质量"指示器（绿/黄/红）。
 * 群通话取当前状态为 CONNECTED/successful 的第一个 PeerConnection。
 *
 * getStats 为异步回调；本方法立即返回最近一次采样结果，并触发下一次采样。
 */
data class WebRTCConnectionStatsSnapshot(
    val rttMs: Long?,
    val packetLossPercent: Double?,
    val availableOutgoingBitrateKbps: Double?
)

internal class WebRTCConnectionStatsCollector(
    private val directConnection: () -> PeerConnection?,
    private val groupConnections: () -> Collection<PeerConnection>,
    private val isReleased: () -> Boolean,
) {
    @Volatile
    private var lastStatsSnapshot: WebRTCConnectionStatsSnapshot? = null

    @Volatile
    private var statsRequestInFlight: Boolean = false
    private val statsRequestGeneration = AtomicLong(0L)

    /** 通话结束/释放时重置采样状态；世代号+1 让在途的 getStats 回调失效。 */
    @Synchronized
    fun reset() {
        lastStatsSnapshot = null
        statsRequestGeneration.incrementAndGet()
        statsRequestInFlight = false
    }

    @Synchronized
    fun getConnectionStatsSnapshot(): WebRTCConnectionStatsSnapshot? {
        if (isReleased()) return null
        val pc = selectStatsPeerConnection()
        if (pc != null && !statsRequestInFlight) {
            val requestGeneration = statsRequestGeneration.incrementAndGet()
            statsRequestInFlight = true
            runCatching {
                pc.getStats { report ->
                    try {
                        if (
                            !isReleased() &&
                            statsRequestGeneration.get() == requestGeneration &&
                            isManagedPeerConnection(pc)
                        ) {
                            lastStatsSnapshot = parseStatsReport(report)
                        }
                    } finally {
                        if (statsRequestGeneration.get() == requestGeneration) {
                            statsRequestInFlight = false
                        }
                    }
                }
            }.onFailure {
                if (statsRequestGeneration.get() == requestGeneration) {
                    statsRequestInFlight = false
                }
            }
        }
        return lastStatsSnapshot
    }

    private fun selectStatsPeerConnection(): PeerConnection? {
        directConnection()?.let { return it }
        groupConnections().firstOrNull()?.let { return it }
        return null
    }

    private fun isManagedPeerConnection(connection: PeerConnection): Boolean =
        directConnection() === connection || groupConnections().any { candidate -> candidate === connection }

    private fun parseStatsReport(report: RTCStatsReport): WebRTCConnectionStatsSnapshot? {
        var rttMs: Long? = null
        var packetsLost = 0L
        var packetsReceived = 0L
        var availableBitrateKbps: Double? = null
        try {
            val statsMap = report.statsMap ?: return null
            for ((_, stats) in statsMap) {
                val type = stats.type?.lowercase().orEmpty()
                val members = stats.members ?: continue
                when (type) {
                    "candidate-pair" -> {
                        val selected = members["selected"] as? Boolean
                            ?: (members["nominated"] as? Boolean)
                        if (selected == true) {
                            readStatNumber(members["currentRoundTripTime"])?.let { rttSec ->
                                rttMs = (rttSec * 1000.0).toLong()
                            }
                            readStatNumber(members["availableOutgoingBitrate"])?.let { bps ->
                                availableBitrateKbps = bps / 1000.0
                            }
                        }
                    }
                    "inbound-rtp" -> {
                        val media = (members["kind"] as? String)
                            ?: (members["mediaType"] as? String)
                            ?: ""
                        if (media == "audio" || media == "video" || media.isEmpty()) {
                            readStatNumber(members["packetsLost"])?.let { packetsLost += it.toLong() }
                            readStatNumber(members["packetsReceived"])?.let { packetsReceived += it.toLong() }
                        }
                    }
                    "remote-inbound-rtp" -> {
                        if (rttMs == null) {
                            readStatNumber(members["roundTripTime"])?.let { rttSec ->
                                rttMs = (rttSec * 1000.0).toLong()
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            return lastStatsSnapshot
        }
        val lossPercent = packetLossPercent(packetsLost, packetsReceived)
        if (rttMs == null && lossPercent == null && availableBitrateKbps == null) {
            return lastStatsSnapshot
        }
        return WebRTCConnectionStatsSnapshot(
            rttMs = rttMs,
            packetLossPercent = lossPercent,
            availableOutgoingBitrateKbps = availableBitrateKbps
        )
    }
}
