package com.maodouchat.server.repository

// 密钥包校验簇：签名与 preKey 合法性校验（原 SignalKeyPackageUpload 扩展按域拆出，零行为改动）。

import java.util.Base64
import org.signal.libsignal.protocol.ecc.Curve

/** 9.298：密钥包上传验签——identity 验 signedPreKey，格式/签名任一异常都拒绝。 */
internal fun verifyKeyPackageSignature(
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
internal fun isValidPreKeyUpload(preKey: PreKeyUpload): Boolean =
    preKey.keyId in 1..16_777_215 &&
        runCatching {
            val encoded = Base64.getDecoder().decode(preKey.publicKeyBase64)
            Curve.decodePoint(encoded, 0)
        }.isSuccess


/** A request containing one id with two different public keys is nondeterministic. */
internal fun hasConflictingPreKeyIds(preKeys: List<PreKeyUpload>): Boolean =
    preKeys.groupBy { it.keyId }.values.any { entries ->
        entries.map { it.publicKeyBase64 }.distinct().size > 1
    }
