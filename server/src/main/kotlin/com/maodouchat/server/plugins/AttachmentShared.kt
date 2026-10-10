package com.maodouchat.server.plugins

import java.security.MessageDigest

// 两簇共用的 SHA-256 小工具（private→internal，零行为改动）。

// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
internal val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
internal fun ByteArray.sha256Hex(): String = sha256ThreadLocal.get().apply { reset() }
// SHA-256 十六进制校验：三处 handler 每次请求都在内联里编译一次，提到文件级复用。
internal val sha256HexRegex = Regex("^[a-f0-9]{64}$")
