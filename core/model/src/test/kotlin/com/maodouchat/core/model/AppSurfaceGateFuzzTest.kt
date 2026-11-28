package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// 11 个 Boolean 端口：读端口 7 个，记账回调 4 个。
private data class GatePorts199b(
    val showFake: Boolean = false,
    val shouldLock: Boolean = false,
    val appLockEnabled: Boolean = false,
    val fakeChatEnabled: Boolean = false,
    val screenSecureEnabled: Boolean = false,
    val secretSurface: Boolean = false,
    val activeSecretSurface: Boolean = false,
)

// op kind: 0 initial, 1 pause, 2 resume, 3 fakeUnlocked, 4 appLockUnlocked。
// pause 用 arg；resume/appLockUnlocked 用 curFake/curLock。
private data class GateOp199b(
    val kind: Int,
    val arg: Boolean = false,
    val curFake: Boolean = false,
    val curLock: Boolean = false,
)

private data class GateCase199b(
    val ports: GatePorts199b,
    val ops: List<GateOp199b> = emptyList(),
)

private data class GateCounters199b(
    var appBg: Int = 0,
    var fakeBg: Int = 0,
    var fakeUnlocked: Int = 0,
    var appUnlocked: Int = 0,
    var showFakeReads: Int = 0,
    var lockReads: Int = 0,
    var appLockReads: Int = 0,
    var fakeEnabledReads: Int = 0,
    var screenSecureReads: Int = 0,
    var secretSurfaceReads: Int = 0,
    var activeSecretReads: Int = 0,
)

private fun encState199b(s: AppSurfaceGateState): String =
    "S:" + (if (s.showFakeChat) "1" else "0") + "," + (if (s.showAppLock) "1" else "0")

private fun encCounters199b(c: GateCounters199b): String = listOf(
    c.appBg, c.fakeBg, c.fakeUnlocked, c.appUnlocked,
    c.showFakeReads, c.lockReads, c.appLockReads, c.fakeEnabledReads,
    c.screenSecureReads, c.secretSurfaceReads, c.activeSecretReads,
).joinToString(",")

// 短路求值也要建模：|| 链读到第一个 true 就停。
private fun gateOracle199b(c: GateCase199b): String {
    val p = c.ports
    val k = GateCounters199b()
    val outs = mutableListOf<String>()
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                k.showFakeReads += 1
                k.lockReads += 1
                outs += encState199b(AppSurfaceGateState(p.showFake, p.shouldLock))
            }
            1 -> {
                if (!op.arg) k.appBg += 1
                k.fakeBg += 1
                var secure: Boolean
                k.appLockReads += 1
                if (p.appLockEnabled) {
                    secure = true
                } else {
                    k.fakeEnabledReads += 1
                    if (p.fakeChatEnabled) {
                        secure = true
                    } else {
                        k.screenSecureReads += 1
                        if (p.screenSecureEnabled) {
                            secure = true
                        } else {
                            k.secretSurfaceReads += 1
                            if (p.secretSurface) {
                                secure = true
                            } else {
                                k.activeSecretReads += 1
                                secure = p.activeSecretSurface
                            }
                        }
                    }
                }
                outs += "B:" + (if (secure) "1" else "0")
            }
            2 -> {
                var fake = op.curFake
                var lock = op.curLock
                if (!fake) {
                    k.showFakeReads += 1
                    if (p.showFake) fake = true
                }
                if (!lock) {
                    k.lockReads += 1
                    if (p.shouldLock) lock = true else k.appUnlocked += 1
                }
                outs += encState199b(AppSurfaceGateState(fake, lock))
            }
            3 -> {
                k.fakeUnlocked += 1
                k.lockReads += 1
                if (p.shouldLock) {
                    outs += encState199b(AppSurfaceGateState(false, true))
                } else {
                    k.appUnlocked += 1
                    outs += encState199b(AppSurfaceGateState(false, false))
                }
            }
            4 -> {
                k.appUnlocked += 1
                outs += encState199b(AppSurfaceGateState(op.curFake, false))
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return outs.joinToString(";") + "|" + encCounters199b(k)
}

private fun gateProd199b(c: GateCase199b): String {
    val p = c.ports
    val k = GateCounters199b()
    val controller = AppSurfaceGateController(
        shouldShowFake = { k.showFakeReads += 1; p.showFake },
        shouldLock = { k.lockReads += 1; p.shouldLock },
        noteAppLockBackground = { k.appBg += 1 },
        noteFakeChatBackground = { k.fakeBg += 1 },
        markFakeUnlocked = { k.fakeUnlocked += 1 },
        markAppUnlocked = { k.appUnlocked += 1 },
        isAppLockEnabled = { k.appLockReads += 1; p.appLockEnabled },
        isFakeChatEnabled = { k.fakeEnabledReads += 1; p.fakeChatEnabled },
        isScreenSecureEnabled = { k.screenSecureReads += 1; p.screenSecureEnabled },
        onSecretChatSurface = { k.secretSurfaceReads += 1; p.secretSurface },
        hasActiveSecretSurface = { k.activeSecretReads += 1; p.activeSecretSurface },
    )
    val outs = mutableListOf<String>()
    for (op in c.ops) {
        when (op.kind) {
            0 -> outs += encState199b(controller.initialState())
            1 -> outs += "B:" + (if (controller.onHostPaused(op.arg)) "1" else "0")
            2 -> outs += encState199b(
                controller.onHostResumed(AppSurfaceGateState(op.curFake, op.curLock)),
            )
            3 -> outs += encState199b(controller.onFakeChatUnlocked())
            4 -> outs += encState199b(
                controller.onAppLockUnlocked(AppSurfaceGateState(op.curFake, op.curLock)),
            )
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return outs.joinToString(";") + "|" + encCounters199b(k)
}

private fun randomPorts199b(r: Random): GatePorts199b = GatePorts199b(
    showFake = r.nextBoolean(),
    shouldLock = r.nextBoolean(),
    appLockEnabled = r.nextBoolean(),
    fakeChatEnabled = r.nextBoolean(),
    screenSecureEnabled = r.nextBoolean(),
    secretSurface = r.nextBoolean(),
    activeSecretSurface = r.nextBoolean(),
)

private fun randomGateCase199b(r: Random): GateCase199b {
    val ops = mutableListOf<GateOp199b>()
    repeat(r.nextInt(0, 9)) {
        when (r.nextInt(5)) {
            0 -> ops += GateOp199b(0)
            1 -> ops += GateOp199b(1, arg = r.nextBoolean())
            2 -> ops += GateOp199b(2, curFake = r.nextBoolean(), curLock = r.nextBoolean())
            3 -> ops += GateOp199b(3)
            else -> ops += GateOp199b(4, curFake = r.nextBoolean(), curLock = r.nextBoolean())
        }
    }
    return GateCase199b(randomPorts199b(r), ops)
}

private fun boundaryGateCase199b(r: Random): GateCase199b {
    val allFalse = GatePorts199b()
    val allTrue = GatePorts199b(true, true, true, true, true, true, true)
    val singleTrue = listOf(
        GatePorts199b(showFake = true),
        GatePorts199b(shouldLock = true),
        GatePorts199b(appLockEnabled = true),
        GatePorts199b(fakeChatEnabled = true),
        GatePorts199b(screenSecureEnabled = true),
        GatePorts199b(secretSurface = true),
        GatePorts199b(activeSecretSurface = true),
    )
    val ports = when (r.nextInt(3)) {
        0 -> allFalse
        1 -> allTrue
        else -> singleTrue[r.nextInt(singleTrue.size)]
    }
    val seqs = listOf(
        listOf(GateOp199b(0)),
        listOf(GateOp199b(1, arg = false), GateOp199b(1, arg = true)),
        listOf(GateOp199b(2, curFake = false, curLock = false)),
        listOf(GateOp199b(2, curFake = true, curLock = true)),
        listOf(GateOp199b(3)),
        listOf(GateOp199b(4, curFake = true, curLock = true)),
        listOf(GateOp199b(0), GateOp199b(1, arg = false), GateOp199b(2), GateOp199b(3), GateOp199b(4)),
        listOf(GateOp199b(3), GateOp199b(3), GateOp199b(4), GateOp199b(2)),
    )
    return GateCase199b(ports, seqs[r.nextInt(seqs.size)])
}

// ---------- tests ----------

class AppSurfaceGateFuzzTest {

    @Test
    fun gateDifferential199() {
        val r = Random(199201)
        repeat(300) {
            val c = randomGateCase199b(r)
            assertEquals(gateOracle199b(c), gateProd199b(c))
        }
    }

    @Test
    fun gateBoundaryDifferential199() {
        val r = Random(199202)
        repeat(300) {
            val c = boundaryGateCase199b(r)
            assertEquals(gateOracle199b(c), gateProd199b(c))
        }
    }

    @Test
    fun gatePinned199() {
        // 全关端口：pause 不安全、resume 记一次 appUnlocked。
        val closed = GateCase199b(
            GatePorts199b(),
            listOf(GateOp199b(0), GateOp199b(1, arg = false), GateOp199b(2)),
        )
        assertEquals(gateOracle199b(closed), gateProd199b(closed))
        // 只有 appLockEnabled：pause 安全且只读一个端口。
        val lockOnly = GateCase199b(
            GatePorts199b(appLockEnabled = true),
            listOf(GateOp199b(1, arg = true)),
        )
        assertEquals(gateOracle199b(lockOnly), gateProd199b(lockOnly))
        // 假解锁回落到锁。
        val fake = GateCase199b(
            GatePorts199b(shouldLock = true),
            listOf(GateOp199b(3)),
        )
        assertEquals(gateOracle199b(fake), gateProd199b(fake))
        // 假解锁无锁可回落：记一次 appUnlocked。
        val noLock = GateCase199b(
            GatePorts199b(),
            listOf(GateOp199b(3), GateOp199b(4, curFake = true, curLock = true)),
        )
        assertEquals(gateOracle199b(noLock), gateProd199b(noLock))
    }
}
