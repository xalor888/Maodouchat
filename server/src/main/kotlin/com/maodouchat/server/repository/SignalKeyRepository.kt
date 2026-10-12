package com.maodouchat.server.repository

/**
 * Signal 密钥仓库
 *
 * 管理用户的加密公钥（身份密钥、签名预密钥、一次性预密钥），按设备隔离。
 * 成员方法按域拆到同包各簇文件（扩展函数），调用点写法不变。
 */
class SignalKeyRepository(
    val deviceRegistry: DeviceRegistry = DeviceRegistry(),
    val identityKeyStore: IdentityKeyStore = IdentityKeyStore(),
    val signedPreKeyStore: SignedPreKeyStore = SignedPreKeyStore(),
    val preKeyStore: PreKeyStore = PreKeyStore()
) : EncryptableDeviceDirectory by deviceRegistry {

    /**
     * Full key package in one transaction so SPK + signature never diverge mid-upload.
     * @return the upload result, including session, device-ID, and identity conflicts.
     */
    enum class UploadKeyPackageResult {
        UPLOADED,
        SESSION_CONFLICT,
        DEVICE_ID_CONFLICT,
        DEVICE_IDENTITY_MISMATCH,
        INVALID_SIGNATURE,
        INVALID_PRE_KEY,
    }
}
