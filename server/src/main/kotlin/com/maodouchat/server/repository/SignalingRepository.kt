package com.maodouchat.server.repository

/**
 * WebRTC 信令仓库
 *
 * 存储和转发 SDP Offer/Answer 和 ICE Candidate
 * 写入 / 消费 / 留存实现已按域拆到同目录簇文件（零行为改动）。
 */
class SignalingRepository {

    companion object {
        // Offer/ICE 可用窗口：客户端振铃 30s，服务端留 150s 让迟到轮询仍能看到挂断。
        const val SIGNALING_TTL_MS = 150_000L
    }
}
