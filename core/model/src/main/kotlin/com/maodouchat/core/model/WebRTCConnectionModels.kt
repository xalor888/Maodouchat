package com.maodouchat.core.model

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
