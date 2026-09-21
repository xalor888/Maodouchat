package com.maodouchat.webrtc

/**
 * ICE 服务器的纯策略（G178 从 WebRTCManager 抽出）。
 *
 * 「配置为空就回落公共 STUN」这个不变量在 `WebRTCManager` 里出现了 **3 次**
 * （字段初始化、`refreshIceServers`、`buildIceServers`）。
 * 三处各写一遍 `ifEmpty { defaultStun() }`，漏改一处就会出现
 * 「TURN 凭据刷新后反而一个服务器都不剩」这类问题。
 */

/** 配置为空时回落公共 STUN；非空时原样返回（同一实例，不复制）。 */
internal fun resolveIceServers(configured: List<CallIceServer>): List<CallIceServer> =
    configured.ifEmpty { CallIceServer.defaultStun() }

/** 把 [CallIceServer] 转成 WebRTC 的 IceServer（含可选的用户名/密码）。 */
internal fun buildWebRtcIceServers(servers: List<CallIceServer>): List<org.webrtc.PeerConnection.IceServer> =
    servers.map { server ->
        org.webrtc.PeerConnection.IceServer.builder(server.urls).apply {
            if (server.username.isNotBlank()) setUsername(server.username)
            if (server.credential.isNotBlank()) setPassword(server.credential)
        }.createIceServer()
    }
