package com.maodouchat.webrtc

import com.maodouchat.core.model.CallIceServer

/** 把 [CallIceServer] 转成 WebRTC 的 IceServer（含可选的用户名/密码）。 */
internal fun buildWebRtcIceServers(servers: List<CallIceServer>): List<org.webrtc.PeerConnection.IceServer> =
    servers.map { server ->
        org.webrtc.PeerConnection.IceServer.builder(server.urls).apply {
            if (server.username.isNotBlank()) setUsername(server.username)
            if (server.credential.isNotBlank()) setPassword(server.credential)
        }.createIceServer()
    }
