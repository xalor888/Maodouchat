package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.IdentityTrustState
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr210c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit210c(b: Boolean): String = if (b) "1" else "0"

private fun opt210c(s: String?): String = s?.let { encStr210c(it) } ?: "~"

// op kind：0 refreshIdentitySafetyState，1 showSafetyCodeDialog，
// 2 dismissSafetyCodeDialog，3 verifyAndTrustIdentity，4 verifyAllDevices。
// safetyMode：0 成功（用 op.devices）、1 失败 RT 带消息、2 失败 RT 空消息、
// 3 直接抛 CE（不包 Result）、4 失败 ApiException（走 onFailure 分支）、
// 5 直接抛 RT（不包 Result；生产此时不复位 isLoading）。
// verifyMode：0 全部成功、1 全部失败、2 仅首台失败。
private data class IdfDevice210c(
    val deviceId: Int,
    val identityKey: String = "",
    val identityFingerprint: String = "",
    val trustState: String = "UNKNOWN",
    val safetyCode: String? = null,
    val isCurrent: Boolean = false,
)

private fun IdfDevice210c.toState(): DeviceSafetyState = DeviceSafetyState(
    deviceId = deviceId,
    identityKey = identityKey,
    identityFingerprint = identityFingerprint,
    trustState = IdentityTrustState.valueOf(trustState),
    safetyCode = safetyCode,
    isCurrent = isCurrent,
)

private fun idfDeviceEnc210c(d: DeviceSafetyState): String = listOf(
    d.deviceId.toString(),
    encStr210c(d.identityKey),
    encStr210c(d.identityFingerprint),
    d.trustState.name,
    opt210c(d.safetyCode),
    bit210c(d.isCurrent),
).joinToString(":")

private data class IdfOp210c(
    val kind: Int,
    val contactId: String = "u1",
    val safetyMode: Int = 0,
    val devices: List<IdfDevice210c> = emptyList(),
    val deviceId: Int? = null,
    val verifyMode: Int = 0,
)

private data class IdfCase210c(
    val owner: String = "me",
    val token: String? = "tok",
    val safetyEnabled: Boolean = true,
    val verifyEnabled: Boolean = true,
    val sessFlipAfter: Int = 999,
    val chatId: String? = "c1",
    val chatIsGroup: Boolean = false,
    val contactId: String = "u1",
    val contactName: String = "Ann",
    val seedDevices: List<IdfDevice210c> = emptyList(),
    val seedIdentityWarning: String? = null,
    val seedSafetyCode: String? = null,
    val seedFingerprint: String? = null,
    val seedTrust: String = "UNKNOWN",
    val seedCanVerify: Boolean = false,
    val seedShowDialog: Boolean = false,
    val seedError: String? = null,
    val ops: List<IdfOp210c> = emptyList(),
)

private fun idfText210c(msg: IdentityVerificationText): String = "T:" + msg.name

private fun idfStateEnc210c(state: ChatDetailUiState): String = listOf(
    opt210c(state.chat?.id),
    bit210c(state.chat?.isGroup ?: false),
    encStr210c(state.contact.id),
    encStr210c(state.contact.name),
    opt210c(state.identityWarning),
    opt210c(state.safetyCode),
    opt210c(state.contactIdentityFingerprint),
    state.trustState.name,
    state.deviceSafetyStates.joinToString(";") { idfDeviceEnc210c(it) },
    bit210c(state.isLoadingDeviceSafety),
    opt210c(state.deviceSafetyWarning),
    bit210c(state.canVerifyIdentity),
    bit210c(state.showSafetyCodeDialog),
    opt210c(state.errorMessage),
).joinToString(":")

// IdentitySafetyPolicy 的纯镜像（aggregateTrust / primaryDevice /
// canVerifyAny / warningKind）。
private fun idfAggregate210c(states: List<DeviceSafetyState>): IdentityTrustState {
    if (states.isEmpty()) return IdentityTrustState.UNKNOWN
    if (states.any { it.trustState == IdentityTrustState.CHANGED }) return IdentityTrustState.CHANGED
    if (states.any { it.trustState == IdentityTrustState.UNKNOWN }) return IdentityTrustState.UNKNOWN
    if (states.all { it.trustState == IdentityTrustState.VERIFIED }) return IdentityTrustState.VERIFIED
    return IdentityTrustState.TRUSTED
}

private fun idfPrimary210c(states: List<DeviceSafetyState>): DeviceSafetyState? =
    states.firstOrNull { it.deviceId == 1 } ?: states.firstOrNull()

private fun idfCanVerify210c(states: List<DeviceSafetyState>): Boolean =
    states.any { !it.safetyCode.isNullOrBlank() && it.trustState != IdentityTrustState.VERIFIED }

private fun idfWarningText210c(trust: IdentityTrustState): String? = when (trust) {
    IdentityTrustState.CHANGED -> idfText210c(IdentityVerificationText.IDENTITY_CHANGED_WARNING)
    IdentityTrustState.UNKNOWN -> idfText210c(IdentityVerificationText.IDENTITY_UNKNOWN_WARNING)
    IdentityTrustState.TRUSTED -> idfText210c(IdentityVerificationText.IDENTITY_TRUSTED_WARNING)
    IdentityTrustState.VERIFIED -> null
}

// resetDirectIdentityForGroup 的纯镜像（ChatGroupSecurityStateController 那份）。
private fun idfResetForGroup210c(state: ChatDetailUiState): ChatDetailUiState = state.copy(
    identityWarning = null,
    safetyCode = null,
    trustState = IdentityTrustState.TRUSTED,
    deviceSafetyStates = emptyList(),
    isLoadingDeviceSafety = false,
    deviceSafetyWarning = null,
    canVerifyIdentity = false,
)

private fun idfOracle210c(c: IdfCase210c): String {
    val effects = mutableListOf<String>()
    var state = ChatDetailUiState(
        contact = User(c.contactId, c.contactName),
        chat = c.chatId?.let { Chat(id = it, isGroup = c.chatIsGroup) },
        identityWarning = c.seedIdentityWarning,
        safetyCode = c.seedSafetyCode,
        contactIdentityFingerprint = c.seedFingerprint,
        trustState = IdentityTrustState.valueOf(c.seedTrust),
        canVerifyIdentity = c.seedCanVerify,
        deviceSafetyStates = c.seedDevices.map { it.toState() },
        showSafetyCodeDialog = c.seedShowDialog,
        errorMessage = c.seedError,
    )
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + idfStateEnc210c(state)
    }
    fun sessionMayContinue(): Boolean {
        effects += "CALL:session"
        val idx = sessCalls++
        return idx < c.sessFlipAfter
    }
    fun safetyStates(op: IdfOp210c, token: String, contactId: String): List<DeviceSafetyState>? {
        effects += "CALL:safety:" + encStr210c(token) + ":" + encStr210c(contactId) + ":" + op.safetyMode
        return when (op.safetyMode) {
            0 -> op.devices.map { it.toState() }
            3 -> throw CancellationException("cancel-boom")
            5 -> throw RuntimeException("boom")
            else -> null
        }
    }
    fun markVerified(op: IdfOp210c, contactId: String, deviceId: Int, index: Int): Boolean {
        val ok = when (op.verifyMode) {
            0 -> true
            1 -> false
            else -> index != 0
        }
        effects += "CALL:verify:" + encStr210c(contactId) + ":" + deviceId + ":" + bit210c(ok)
        return ok
    }
    // 生产 refreshIdentitySafetyState 的逐分支镜像。返回值：0 正常结束或提前返回、
    // 1 抛了 CE、2 抛了 RT。CE 在生产 launch 内被静默吞掉（不记 token）；RT 由
    // handler 记一次（调用方 join 时若重抛，外层 launch 再记一次）。
    fun refresh(op: IdfOp210c, contactId: String): Int {
        if (state.chat?.isGroup == true) {
            state = idfResetForGroup210c(state)
            return 0
        }
        if (contactId.isBlank()) return 0
        val owner = c.owner
        if (owner.isBlank() || owner == "me" || !sessionMayContinue()) return 0
        state = state.copy(isLoadingDeviceSafety = true, deviceSafetyWarning = null)
        try {
            if (!sessionMayContinue()) {
                state = state.copy(isLoadingDeviceSafety = false)
                return 0
            }
            val token = c.token.orEmpty()
            val states = try {
                safetyStates(op, token, contactId)
            } catch (e: CancellationException) {
                throw e
            }
            // (B) fold 前的检查：失败则复位 isLoading 再返回。
            if (!sessionMayContinue()) {
                state = state.copy(isLoadingDeviceSafety = false)
                return 0
            }
            if (states == null) {
                // (C') onFailure 分支的检查：失败直接返回，isLoading 不复位。
                if (!sessionMayContinue()) return 0
                state = state.copy(
                    isLoadingDeviceSafety = false,
                    deviceSafetyWarning = idfText210c(IdentityVerificationText.SAFETY_DEVICES_LOAD_FAILED),
                )
                return 0
            }
            // (C) onSuccess 分支的检查：失败直接返回，isLoading 不复位。
            if (!sessionMayContinue()) return 0
            val trust = idfAggregate210c(states)
            val primary = idfPrimary210c(states)
            state = state.copy(
                identityWarning = idfWarningText210c(trust),
                safetyCode = primary?.safetyCode,
                contactIdentityFingerprint = primary?.identityFingerprint,
                trustState = trust,
                deviceSafetyStates = states,
                isLoadingDeviceSafety = false,
                deviceSafetyWarning = null,
                canVerifyIdentity = idfCanVerify210c(states),
            )
            return 0
        } catch (e: CancellationException) {
            state = state.copy(isLoadingDeviceSafety = false)
            return 1
        } catch (e: Exception) {
            // 生产的 launch 只接 CE；其余异常直接外抛，isLoading 不复位。
            // 此处一次 THROW 对应内层 launch 的 handler。
            effects += "THROW:" + e.javaClass.simpleName
            return 2
        }
    }
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> refresh(op, op.contactId)
                1 -> {
                    if (!c.safetyEnabled) {
                        state = state.copy(errorMessage = idfText210c(IdentityVerificationText.SAFETY_CODE_DISABLED))
                    } else {
                        state = state.copy(showSafetyCodeDialog = true)
                        refresh(op, state.contact.id)
                    }
                }
                2 -> {
                    state = state.copy(showSafetyCodeDialog = false)
                }
                3 -> {
                    val contactId = state.contact.id
                    if (contactId.isBlank()) {
                        // 生产直接返回。
                    } else {
                        val owner = c.owner
                        if (owner.isBlank() || owner == "me" || !sessionMayContinue()) {
                            // 生产直接返回。
                        } else {
                            val target = op.deviceId ?: 1
                            if (!sessionMayContinue()) {
                                // 生产直接返回。
                            } else {
                                val ok = markVerified(op, contactId, target, 0)
                                if (!sessionMayContinue()) {
                                    // 生产直接返回。
                                } else if (ok) {
                                    // join() 不重抛内层刷新的异常：RT 只由内层 launch 的 handler 记一次，
                                    // CE 不可观测；无论哪种结果外层都继续读指纹。
                                    refresh(op, contactId)
                                    if (c.verifyEnabled) {
                                        val fp = state.contactIdentityFingerprint?.takeIf { it.isNotBlank() }
                                        if (fp != null) {
                                            effects += "CALL:fp:" + encStr210c(fp)
                                        }
                                    }
                                } else {
                                    state = state.copy(
                                        deviceSafetyWarning = idfText210c(IdentityVerificationText.TRUST_FAILED),
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    val contactId = state.contact.id
                    if (contactId.isBlank()) {
                        // 生产直接返回。
                    } else {
                        val devices = state.deviceSafetyStates
                        if (devices.isEmpty()) {
                            // 生产直接返回。
                        } else {
                            val owner = c.owner
                            if (owner.isBlank() || owner == "me" || !sessionMayContinue()) {
                                // 生产直接返回。
                            } else {
                                var allOk = true
                                var aborted = false
                                var idx = 0
                                for (device in devices) {
                                    if (!sessionMayContinue()) {
                                        aborted = true
                                        break
                                    }
                                    val ok = markVerified(op, contactId, device.deviceId, idx)
                                    if (!ok) allOk = false
                                    idx++
                                }
                                if (aborted) {
                                    // 会话中途失效：生产直接返回。
                                } else if (!sessionMayContinue()) {
                                    // 生产直接返回。
                                } else if (allOk) {
                                    refresh(op, contactId)
                                } else {
                                    state = state.copy(
                                        deviceSafetyWarning = idfText210c(IdentityVerificationText.TRUST_FAILED),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun idfProd210c(c: IdfCase210c): String {
    val effects = mutableListOf<String>()
    val flow = kotlinx.coroutines.flow.MutableStateFlow(
        ChatDetailUiState(
            contact = User(c.contactId, c.contactName),
            chat = c.chatId?.let { Chat(id = it, isGroup = c.chatIsGroup) },
            identityWarning = c.seedIdentityWarning,
            safetyCode = c.seedSafetyCode,
            contactIdentityFingerprint = c.seedFingerprint,
            trustState = IdentityTrustState.valueOf(c.seedTrust),
            canVerifyIdentity = c.seedCanVerify,
            deviceSafetyStates = c.seedDevices.map { it.toState() },
            showSafetyCodeDialog = c.seedShowDialog,
            errorMessage = c.seedError,
        ),
    )
    var sessCalls = 0
    fun snap(prefix: String) {
        effects += prefix + ":" + idfStateEnc210c(flow.value)
    }
    val handler = CoroutineExceptionHandler { _, e ->
        effects += "THROW:" + e.javaClass.simpleName
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
    fun drain() {
        runBlocking {
            repeat(100) {
                val kids = scope.coroutineContext[Job]?.children?.toList() ?: emptyList()
                if (kids.isEmpty()) return@runBlocking
                for (child in kids) runCatching { child.join() }
            }
        }
    }
    var curOp = IdfOp210c(0)
    var verifyCalls = 0
    val controller = IdentityVerificationController(
        scope = scope,
        resetDirectIdentityForGroup = { st -> idfResetForGroup210c(st) },
        uiState = flow,
        text = { msg -> idfText210c(msg) },
        currentUserId = { c.owner },
        sessionToken = { c.token },
        sessionMayContinue = {
            effects += "CALL:session"
            val idx = sessCalls++
            idx < c.sessFlipAfter
        },
        safetyCodeEnabled = { c.safetyEnabled },
        deviceVerifyEnabled = { c.verifyEnabled },
        markFingerprintVerified = { fp -> effects += "CALL:fp:" + encStr210c(fp) },
        getRemoteDeviceSafetyStates = { token, contactId ->
            effects += "CALL:safety:" + encStr210c(token) + ":" + encStr210c(contactId) + ":" + curOp.safetyMode
            when (curOp.safetyMode) {
                0 -> Result.success(curOp.devices.map { it.toState() })
                1 -> Result.failure(RuntimeException("boom"))
                2 -> Result.failure(RuntimeException())
                3 -> throw CancellationException("cancel-boom")
                5 -> throw RuntimeException("boom")
                else -> Result.failure(RuntimeException("api"))
            }
        },
        markIdentityVerified = { contactId, deviceId ->
            val ok = when (curOp.verifyMode) {
                0 -> true
                1 -> false
                else -> {
                    val idx = verifyCalls++
                    idx != 0
                }
            }
            effects += "CALL:verify:" + encStr210c(contactId) + ":" + deviceId + ":" + bit210c(ok)
            ok
        },
        ioDispatcher = Dispatchers.Unconfined,
    )
    for (op in c.ops) {
        curOp = op
        verifyCalls = 0
        try {
            when (op.kind) {
                0 -> controller.refreshIdentitySafetyState(op.contactId)
                1 -> controller.showSafetyCodeDialog()
                2 -> controller.dismissSafetyCodeDialog()
                3 -> controller.verifyAndTrustIdentity(op.deviceId)
                else -> controller.verifyAllDevices()
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        drain()
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val IDF_TRUSTS210c = listOf("UNKNOWN", "TRUSTED", "VERIFIED", "CHANGED")

private fun randomIdfDevice210c(r: Random): IdfDevice210c = IdfDevice210c(
    deviceId = listOf(1, 2, 3)[r.nextInt(3)],
    identityKey = listOf("", "k1", "k2")[r.nextInt(3)],
    identityFingerprint = listOf("", "fp1", "fp2")[r.nextInt(3)],
    trustState = IDF_TRUSTS210c[r.nextInt(4)],
    safetyCode = listOf<String?>(null, "1234", "", "5678")[r.nextInt(4)],
    isCurrent = r.nextInt(2) == 0,
)

private fun randomIdfOp210c(r: Random): IdfOp210c = IdfOp210c(
    kind = r.nextInt(5),
    contactId = listOf("u1", "u2", "", " ")[r.nextInt(4)],
    safetyMode = r.nextInt(6),
    devices = List(r.nextInt(0, 4)) { randomIdfDevice210c(r) },
    deviceId = listOf<Int?>(null, 1, 2, 3)[r.nextInt(4)],
    verifyMode = r.nextInt(3),
)

private fun randomIdfCase210c(r: Random): IdfCase210c = IdfCase210c(
    owner = listOf("me", "u1", "owner", "")[r.nextInt(4)],
    token = listOf<String?>("tok", "", null)[r.nextInt(3)],
    safetyEnabled = r.nextInt(5) != 0,
    verifyEnabled = r.nextInt(5) != 0,
    sessFlipAfter = listOf(0, 1, 2, 3, 4, 5, 999)[r.nextInt(7)],
    chatId = listOf<String?>("c1", null, "g1")[r.nextInt(3)],
    chatIsGroup = r.nextInt(3) == 0,
    contactId = listOf("u1", "u2")[r.nextInt(2)],
    contactName = listOf("Ann", "Bob", "")[r.nextInt(3)],
    seedDevices = List(r.nextInt(0, 4)) { randomIdfDevice210c(r) },
    seedIdentityWarning = listOf<String?>(null, "W")[r.nextInt(2)],
    seedSafetyCode = listOf<String?>(null, "0000")[r.nextInt(2)],
    seedFingerprint = listOf<String?>(null, "seedfp")[r.nextInt(2)],
    seedTrust = IDF_TRUSTS210c[r.nextInt(4)],
    seedCanVerify = r.nextInt(2) == 0,
    seedShowDialog = r.nextInt(4) == 0,
    seedError = listOf<String?>(null, "E")[r.nextInt(2)],
    ops = List(r.nextInt(0, 9)) { randomIdfOp210c(r) },
)

private fun boundaryIdfCase210c(r: Random): IdfCase210c {
    val devs = listOf(
        IdfDevice210c(1, "k1", "fp1", "VERIFIED", "1111"),
        IdfDevice210c(2, "k2", "fp2", "UNKNOWN", "2222"),
    )
    val base = IdfCase210c(seedDevices = devs)
    return when (r.nextInt(27)) {
        0 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")))
        1 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 1)))
        2 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 3)))
        3 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 4)))
        4 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "")))
        5 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")), chatIsGroup = true)
        6 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")), owner = "me")
        7 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")), sessFlipAfter = 0)
        8 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")), sessFlipAfter = 1)
        9 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", devices = emptyList())))
        10 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", devices = listOf(IdfDevice210c(2, "k9", "fp9", "CHANGED", "9999")))))
        11 -> base.copy(ops = listOf(IdfOp210c(1)))
        12 -> base.copy(ops = listOf(IdfOp210c(1)), safetyEnabled = false)
        13 -> base.copy(ops = listOf(IdfOp210c(2)), seedShowDialog = true)
        14 -> base.copy(ops = listOf(IdfOp210c(3, deviceId = 1)))
        15 -> base.copy(ops = listOf(IdfOp210c(3, deviceId = 1, verifyMode = 1)))
        16 -> base.copy(ops = listOf(IdfOp210c(3, deviceId = null)))
        17 -> base.copy(ops = listOf(IdfOp210c(3, deviceId = 2, verifyMode = 2)))
        18 -> base.copy(ops = listOf(IdfOp210c(4)))
        19 -> base.copy(ops = listOf(IdfOp210c(4, verifyMode = 1)))
        20 -> base.copy(ops = listOf(IdfOp210c(4, verifyMode = 2)))
        21 -> base.copy(ops = listOf(IdfOp210c(3, deviceId = 1)), sessFlipAfter = 2)
        22 -> base.copy(ops = listOf(IdfOp210c(4)), sessFlipAfter = 2)
        23 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 0, devices = devs)))
        24 -> base.copy(ops = listOf(IdfOp210c(1, safetyMode = 3)))
        25 -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 5)))
        else -> base.copy(ops = listOf(IdfOp210c(0, contactId = "u1")), token = null)
    }
}

// ---------- tests ----------

class IdentityVerificationControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(210305)
        repeat(300) {
            val c = randomIdfCase210c(r)
            assertEquals(idfOracle210c(c), idfProd210c(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(210306)
        repeat(200) {
            val c = boundaryIdfCase210c(r)
            assertEquals(idfOracle210c(c), idfProd210c(c))
        }
    }

    @Test
    fun pinnedCases() {
        val devs = listOf(
            IdfDevice210c(1, "k1", "fp1", "VERIFIED", "1111"),
            IdfDevice210c(2, "k2", "fp2", "UNKNOWN", "2222"),
        )
        val cases = listOf(
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(0, contactId = "u1"))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 1))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(0, contactId = "u1", safetyMode = 3))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(0, contactId = "u1")), chatIsGroup = true),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(1))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(1)), safetyEnabled = false),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(2)), seedShowDialog = true),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(3, deviceId = 1))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(3, deviceId = 1, verifyMode = 1))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(4))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(4, verifyMode = 1))),
            IdfCase210c(seedDevices = devs, ops = listOf(IdfOp210c(0, contactId = "u1")), sessFlipAfter = 1),
            IdfCase210c(seedDevices = devs, ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(idfOracle210c(c), idfProd210c(c))
        }
    }
}
