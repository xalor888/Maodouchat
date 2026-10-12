package com.maodouchat.server.repository

// key package 上传与校验簇：原 SignalKeyBundles 顶层声明按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.SignalKeys
import com.maodouchat.server.repository.SignalKeyRepository.UploadKeyPackageResult
import com.maodouchat.server.service.IdentitySecurityEventPolicy
import com.maodouchat.server.service.IdentitySecurityEventRecorder
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.signal.libsignal.protocol.ecc.Curve

fun SignalKeyRepository.uploadKeyPackage(
    userId: String,
    authSessionId: String,
    deviceId: Int,
    identityKey: String,
    registrationId: Int,
    signedPreKeyId: Int,
    signedPreKey: String,
    signedPreKeySignature: String,
    preKeys: List<PreKeyUpload>,
    deviceName: String? = null,
): UploadKeyPackageResult {
    // 9.298：上传前验签——signedPreKey 签名必须用 identityKey 验证通过。
    // 实测事故：旧版客户端 identity 重生后残留旧 SPK 上传，坏 bundle 入库后所有
    // 与该用户建会话的对端永远报「密钥包无效」（发图/发消息全挂）。入库前拦住
    if (!verifyKeyPackageSignature(identityKey, signedPreKey, signedPreKeySignature)) {
        return UploadKeyPackageResult.INVALID_SIGNATURE
    }
    // Validate every one-time public key before opening the write transaction.  The route
    // performs shape checks as well, but repository callers (jobs/tests) must get the same
    // deterministic result and, importantly, must not leave a partially published bundle.
    if (!preKeys.all(::isValidPreKeyUpload) || hasConflictingPreKeyIds(preKeys)) {
        return UploadKeyPackageResult.INVALID_PRE_KEY
    }
    return transaction {
        com.maodouchat.server.db.Users.selectAll()
            .where { com.maodouchat.server.db.Users.id eq userId }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction UploadKeyPackageResult.SESSION_CONFLICT

        val authSession = AuthSessions.selectAll().where {
            (AuthSessions.id eq authSessionId) and
                (AuthSessions.userId eq userId)
        }.forUpdate().firstOrNull()
            ?: return@transaction UploadKeyPackageResult.SESSION_CONFLICT
        if (authSession[AuthSessions.revokedAt] != null) {
            return@transaction UploadKeyPackageResult.SESSION_CONFLICT
        }
        val boundDeviceId = authSession[AuthSessions.signalDeviceId]
        if (boundDeviceId != null && boundDeviceId != deviceId) {
            return@transaction UploadKeyPackageResult.SESSION_CONFLICT
        }
        val existingIdentity = SignalKeys.selectAll().where {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "identity_key")
        }.forUpdate().firstOrNull()?.get(SignalKeys.keyData)
        // An authenticated session is already pinned to this device slot.  A different
        // identity on that slot is a stale/corrupt local store, not a free identity-migration
        // candidate: returning IDENTITY_MISMATCH would make the client switch slots even
        // though every subsequent upload is rejected by the session binding.
        if (boundDeviceId != null && existingIdentity != null && existingIdentity != identityKey) {
            recordIdentityMismatchAndRevokeSessionInTx(
                userId = userId,
                deviceId = deviceId,
                authSessionId = authSessionId,
            )
            return@transaction UploadKeyPackageResult.SESSION_CONFLICT
        }
        if (existingIdentity != null && existingIdentity != identityKey) {
            recordIdentityMismatchAndRevokeSessionInTx(
                userId = userId,
                deviceId = deviceId,
                authSessionId = authSessionId,
            )
            return@transaction UploadKeyPackageResult.DEVICE_IDENTITY_MISMATCH
        }
        if (boundDeviceId == null) {
            val occupiedByAnotherSession = AuthSessions.selectAll().where {
                (AuthSessions.userId eq userId) and
                    (AuthSessions.signalDeviceId eq deviceId) and
                    (AuthSessions.id neq authSessionId) and
                    AuthSessions.revokedAt.isNull()
            }.forUpdate().firstOrNull() != null
            if (occupiedByAnotherSession) {
                return@transaction UploadKeyPackageResult.DEVICE_ID_CONFLICT
            }
        }
        if (boundDeviceId == null) {
            AuthSessions.update({ AuthSessions.id eq authSessionId }) {
                it[signalDeviceId] = deviceId
                it[updatedAt] = System.currentTimeMillis()
            }
        }

        val identityFirstPublish = existingIdentity == null
        upsertSingleKeyInTx(userId, deviceId, "identity_key", identityKey)
        if (identityFirstPublish) {
            IdentitySecurityEventRecorder.recordInTx(
                userId = userId,
                action = IdentitySecurityEventPolicy.ACTION_PUBLISHED,
                detail = IdentitySecurityEventPolicy.publishedDetail(deviceId),
            )
        }
        upsertSingleKeyInTx(userId, deviceId, "registration_id", registrationId.toString())
        upsertSingleKeyInTx(userId, deviceId, "device_id", deviceId.toString())

        SignalKeys.deleteWhere {
            (SignalKeys.userId eq userId) and
                (SignalKeys.deviceId eq deviceId) and
                (SignalKeys.keyType eq "signed_pre_key")
        }
        SignalKeys.insert {
            it[SignalKeys.id] = "sk_${UUID.randomUUID()}"
            it[SignalKeys.userId] = userId
            it[SignalKeys.deviceId] = deviceId
            it[keyType] = "signed_pre_key"
            it[keyData] = signedPreKey
            it[SignalKeys.keyId] = signedPreKeyId
            it[createdAt] = System.currentTimeMillis()
        }
        upsertSingleKeyInTx(userId, deviceId, "signed_pre_key_signature", signedPreKeySignature)

        preKeys.forEach { preKeyStore.uploadPreKeyInTx(userId, deviceId, it) }

        touchDeviceInTx(userId, deviceId, deviceName)
        UploadKeyPackageResult.UPLOADED
    }
}


/** 9.298：密钥包上传验签——identity 验 signedPreKey，格式/签名任一异常都拒绝。 */
private fun verifyKeyPackageSignature(
    identityKeyBase64: String,
    signedPreKeyBase64: String,
    signatureBase64: String
): Boolean = runCatching {
    val identityPublic = Curve.decodePoint(Base64.getDecoder().decode(identityKeyBase64), 0)
    val signedPreKeyPublic = Curve.decodePoint(Base64.getDecoder().decode(signedPreKeyBase64), 0)
    val signature = Base64.getDecoder().decode(signatureBase64)
    Curve.verifySignature(identityPublic, signedPreKeyPublic.serialize(), signature)
}.getOrDefault(false)


/** Validate the actual Curve point, not merely that the field looks like Base64. */
private fun isValidPreKeyUpload(preKey: PreKeyUpload): Boolean =


/** A request containing one id with two different public keys is nondeterministic. */
private fun hasConflictingPreKeyIds(preKeys: List<PreKeyUpload>): Boolean =
