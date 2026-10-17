package com.maodouchat.server.plugins

import kotlinx.coroutines.sync.Mutex

/**
 * 单 session 发送互斥锁。Ktor WebSocketSession.send 不保证并发安全，按 session
 * 串行化发送避免多路 fanout 帧交错。
 */
class SessionSendLock(val mutex: Mutex = Mutex(), var users: Int = 0)
