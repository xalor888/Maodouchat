package com.maodouchat.security

/**
 * 扫码「安全码 / 身份指纹」核验（U02 延伸：自 `ui/screen/contacts/ContactSubScreens`
 * 迁出的决策 + app 单例访问收口）。
 *
 * 为什么收出来：这段核验原先整个写在 ScanScreen 的 `scope.launch` 里，直接 `as MaodouchatApp`
 * 后调 5 个 `signalProtocol` 入口，**零测试覆盖**。它编码的是 10 种核验结论（含
 * 「扫到别的设备」「QR 与本机指纹不符」这类反诈语义），错一个分支不会崩、只会**给出错误的安全结论**。
 *
 * 现在的形状：
 * - [SafetyScanRequest]：仅承载 QR 载荷里参与核验的字段（不依赖 ui 的 QrTarget 类型，
 *   非 ui 层不 import ui）；
 * - [SafetySignalPort]：核验需要的一小组本机信号能力（生产实现 [SignalIdentityAccess]，JVM 单测用假实现）；
 * - [QrSafetyScanEvaluator.evaluate]：纯决策，逐字复刻原 when 链——包括
 *   **先取本机 deviceId（即使会话已过期也会调用）** 这个调用顺序。
 */
enum class SafetyScanStatus {
    SESSION_EXPIRED,
    WRONG_ACCOUNT,
    WRONG_DEVICE,
    NO_SESSION,
    WRONG_DEVICE_QR,
    FINGERPRINT_MATCH,
    FINGERPRINT_MISMATCH,
    INVALID_CODE,
    CODE_MATCH,
    CODE_MISMATCH,
}

/** QR 载荷里参与核验的字段（由 ui 侧从 `QrCodeGenerator.QrTarget.Safety` 逐字映射）。 */
data class SafetyScanRequest(
    val ownerUserId: String,
    val ownerDeviceId: Int,
    val peerUserId: String,
    val peerDeviceId: Int,
    val ownerIdentityFingerprint: String?,
    val peerIdentityFingerprint: String?,
    val safetyCode: String?,
)

/** 核验所需的本机信号能力。 */
interface SafetySignalPort {
    fun deviceId(): Int
    fun localIdentityFingerprint(): String
    fun remoteIdentityFingerprint(remoteUserId: String, deviceId: Int): String?
    fun safetyCode(remoteUserId: String, deviceId: Int): String?
}

object QrSafetyScanEvaluator {

    fun evaluate(
        request: SafetyScanRequest,
        currentUserId: String,
        signal: SafetySignalPort,
    ): SafetyScanStatus {
        // 原实现即使在会话过期分支之前也会读一次本机 deviceId（廉价本地读），保持同一调用顺序。
        val currentDeviceId = signal.deviceId()
        return when {
            currentUserId.isBlank() -> SafetyScanStatus.SESSION_EXPIRED
            request.peerUserId != currentUserId -> SafetyScanStatus.WRONG_ACCOUNT
            request.peerDeviceId != currentDeviceId -> SafetyScanStatus.WRONG_DEVICE
            else -> {
                val ownerFingerprint = request.ownerIdentityFingerprint
                val peerFingerprint = request.peerIdentityFingerprint
                if (!ownerFingerprint.isNullOrBlank() && !peerFingerprint.isNullOrBlank()) {
                    val localPeerFingerprint = signal.localIdentityFingerprint()
                    val localOwnerFingerprint = signal.remoteIdentityFingerprint(
                        request.ownerUserId,
                        request.ownerDeviceId,
                    )
                    when {
                        localOwnerFingerprint.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                        localPeerFingerprint.normalizedFingerprint() != peerFingerprint.normalizedFingerprint() ->
                            SafetyScanStatus.WRONG_DEVICE_QR

                        localOwnerFingerprint.normalizedFingerprint() == ownerFingerprint.normalizedFingerprint() ->
                            SafetyScanStatus.FINGERPRINT_MATCH

                        else -> SafetyScanStatus.FINGERPRINT_MISMATCH
                    }
                } else {
                    val localCode = signal.safetyCode(request.ownerUserId, request.ownerDeviceId)
                    when {
                        localCode.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                        request.safetyCode.isNullOrBlank() -> SafetyScanStatus.INVALID_CODE
                        localCode.normalizedSafetyCode() == request.safetyCode.normalizedSafetyCode() ->
                            SafetyScanStatus.CODE_MATCH

                        else -> SafetyScanStatus.CODE_MISMATCH
                    }
                }
            }
        }
    }
}

/** 指纹比较前的归一化（原为 ContactSubScreens 私有扩展，逐字迁移）。 */
internal fun String.normalizedFingerprint(): String = filter { it.isLetterOrDigit() }.lowercase()

/** 安全码比较前的归一化（原为 ContactSubScreens 私有扩展，逐字迁移）。 */
internal fun String.normalizedSafetyCode(): String = filter { it.isDigit() }
