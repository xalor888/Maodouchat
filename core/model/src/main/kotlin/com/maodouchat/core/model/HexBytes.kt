package com.maodouchat.core.model

// 每字节固定两位小写十六进制，不补零的话 0x0A 会变成 "a"，长度漂移。
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
