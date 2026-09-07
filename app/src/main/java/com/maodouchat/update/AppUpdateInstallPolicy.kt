package com.maodouchat.update

/**
 * Install-time update gates (pure): package/signer trust + APK archive versionCode.
 * Offer-time HTTPS/SHA rules stay in [AppUpdatePolicy]; this closes downgrade /
 * wrong-offer APK after download.
 */
object AppUpdateInstallPolicy {

    /**
     * Archive must match the offered remote versionCode and strictly exceed the
     * currently installed versionCode (no sideload downgrade / swapped build).
     */
    fun acceptsArchiveVersion(
        expectedRemoteVersionCode: Int,
        archiveVersionCode: Long,
        installedVersionCode: Long,
    ): Boolean {
        if (expectedRemoteVersionCode <= 0) return false
        if (archiveVersionCode <= 0L) return false
        if (archiveVersionCode != expectedRemoteVersionCode.toLong()) return false
        return archiveVersionCode > installedVersionCode
    }

    fun isPackageAndSignerTrusted(
        installedPackageName: String,
        archivePackageName: String?,
        installedSignerDigests: Set<String>,
        archiveSignerDigests: Set<String>,
    ): Boolean {
        if (installedPackageName.isBlank()) return false
        if (archivePackageName.isNullOrBlank()) return false
        if (archivePackageName != installedPackageName) return false
        if (archiveSignerDigests.isEmpty()) return false
        return archiveSignerDigests == installedSignerDigests
    }
}
