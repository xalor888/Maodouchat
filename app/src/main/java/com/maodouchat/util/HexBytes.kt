package com.maodouchat.util

/**
 * 字节数组的小写十六进制字符串（G181 从 7 处私有副本收敛而来）。
 *
 * 用于附件摘要、错误码等需要稳定可读指纹的场景。
 * 约定：**小写、每字节两位、补零**——补零是硬要求，
 * 否则 `0x0A` 会变成 "a" 而 `0xAA` 也是 "aa" 之外的歧义，
 * 且不同长度的串可能表示同一段内容。
 */
internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
