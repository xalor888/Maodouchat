package com.maodouchat.core.model

/** 配置为空时回落公共 STUN；非空时原样返回（同一实例，不复制）。 */
fun resolveIceServers(configured: List<CallIceServer>): List<CallIceServer> =
    configured.ifEmpty { CallIceServer.defaultStun() }
