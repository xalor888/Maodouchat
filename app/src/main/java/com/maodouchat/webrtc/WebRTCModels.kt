package com.maodouchat.webrtc

/**
 * 通话状态
 */
enum class CallState {
    IDLE, CALLING, RINGING, CONNECTED, DISCONNECTED
}

/**
 * 通话类型
 */
enum class CallType { AUDIO, VIDEO, GROUP;

    companion object {
        /**
         * 从 SDP 文本推断通话类型。仅当存在真实视频媒体行（以 `m=video` 开头且端口非 0）
         * 时判为视频，避免把属性行或被拒绝/禁用的 `m=video 0` 当作视频。
         */
        fun detectFromSdp(sdp: String?): CallType {
            if (sdp.isNullOrBlank()) return AUDIO
            for (line in sdp.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("m=video", ignoreCase = true)) {
                    // removePrefix 没有 ignoreCase 重载，标签已忽略大小写匹配，直接按长度 7 剥离端口。
                    val port = trimmed.substring(7).trimStart()
                        .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    if (port != 0) return VIDEO
                }
            }
            return AUDIO
        }
    }
}

enum class GroupPeerConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED, REJECTED, BUSY, NO_ANSWER }

data class CallIceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
) {
    companion object {
        fun defaultStun(): List<CallIceServer> = listOf(
            CallIceServer(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
        )

        /** True when no TURN/TURNS URL is present (NAT may fail; public STUN only). */
        fun isStunOnly(servers: List<CallIceServer>): Boolean {
            if (servers.isEmpty()) return true
            return servers.none { server ->
                server.urls.any { url ->
                    url.startsWith("turn:", ignoreCase = true) ||
                        url.startsWith("turns:", ignoreCase = true)
                }
            }
        }
    }
}

class WebRTCException(message: String) : IllegalStateException(message)
