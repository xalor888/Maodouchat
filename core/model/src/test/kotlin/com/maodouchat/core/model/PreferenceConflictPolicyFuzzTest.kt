package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzz / random-oracle tests for [PreferenceConflictPolicy] (checklist line 931, 31st block).
 * Test-only, zero production changes. The oracle below re-implements the resolve
 * rules from their specification; a Python mini-model differentially checks the
 * oracle transcription against an independent production transcription.
 * All random cases pass an explicit currentTimestampMs so results are reproducible.
 */
class PreferenceConflictPolicyFuzzTest {

    private val fieldNames = listOf(
        "themeMode", "themeStyle", "accentColor", "languageMode",
        "chatWallpaper", "chatFontScale", "linkPreviewEnabled", "unreadPriorityEnabled",
        "writingStyleEnabled", "writingStylePreset", "writingStyleCustom", "appLockTimeoutMinutes",
        "screenSecureEnabled", "sensitiveGateEnabled"
    )

    private fun prefsField(p: MultiDevicePreferences, idx: Int): Any? = when (idx) {
        0 -> p.themeMode
        1 -> p.themeStyle
        2 -> p.accentColor
        3 -> p.languageMode
        4 -> p.chatWallpaper
        5 -> p.chatFontScale
        6 -> p.linkPreviewEnabled
        7 -> p.unreadPriorityEnabled
        8 -> p.writingStyleEnabled
        9 -> p.writingStylePreset
        10 -> p.writingStyleCustom
        11 -> p.appLockTimeoutMinutes
        12 -> p.screenSecureEnabled
        13 -> p.sensitiveGateEnabled
        else -> error("bad field idx $idx")
    }

    private fun patchField(p: PreferencesPatch, idx: Int): Any? = when (idx) {
        0 -> p.themeMode
        1 -> p.themeStyle
        2 -> p.accentColor
        3 -> p.languageMode
        4 -> p.chatWallpaper
        5 -> p.chatFontScale
        6 -> p.linkPreviewEnabled
        7 -> p.unreadPriorityEnabled
        8 -> p.writingStyleEnabled
        9 -> p.writingStylePreset
        10 -> p.writingStyleCustom
        11 -> p.appLockTimeoutMinutes
        12 -> p.screenSecureEnabled
        13 -> p.sensitiveGateEnabled
        else -> error("bad field idx $idx")
    }

    private fun withPrefsField(p: MultiDevicePreferences, idx: Int, v: Any?): MultiDevicePreferences = when (idx) {
        0 -> p.copy(themeMode = v as String)
        1 -> p.copy(themeStyle = v as String)
        2 -> p.copy(accentColor = v as String)
        3 -> p.copy(languageMode = v as String)
        4 -> p.copy(chatWallpaper = v as String)
        5 -> p.copy(chatFontScale = v as String)
        6 -> p.copy(linkPreviewEnabled = v as Boolean)
        7 -> p.copy(unreadPriorityEnabled = v as Boolean)
        8 -> p.copy(writingStyleEnabled = v as Boolean)
        9 -> p.copy(writingStylePreset = v as String)
        10 -> p.copy(writingStyleCustom = v as String)
        11 -> p.copy(appLockTimeoutMinutes = v as Long)
        12 -> p.copy(screenSecureEnabled = v as Boolean)
        13 -> p.copy(sensitiveGateEnabled = v as Boolean)
        else -> error("bad field idx $idx")
    }

    private fun withPatchField(p: PreferencesPatch, idx: Int, v: Any?): PreferencesPatch = when (idx) {
        0 -> p.copy(themeMode = v as String?)
        1 -> p.copy(themeStyle = v as String?)
        2 -> p.copy(accentColor = v as String?)
        3 -> p.copy(languageMode = v as String?)
        4 -> p.copy(chatWallpaper = v as String?)
        5 -> p.copy(chatFontScale = v as String?)
        6 -> p.copy(linkPreviewEnabled = v as Boolean?)
        7 -> p.copy(unreadPriorityEnabled = v as Boolean?)
        8 -> p.copy(writingStyleEnabled = v as Boolean?)
        9 -> p.copy(writingStylePreset = v as String?)
        10 -> p.copy(writingStyleCustom = v as String?)
        11 -> p.copy(appLockTimeoutMinutes = v as Long?)
        12 -> p.copy(screenSecureEnabled = v as Boolean?)
        13 -> p.copy(sensitiveGateEnabled = v as Boolean?)
        else -> error("bad field idx $idx")
    }

    private fun oracleResolve(
        sessionOwnerUserId: String,
        local: MultiDevicePreferences,
        remote: MultiDevicePreferences,
        patch: PreferencesPatch?,
        now: Long
    ): ConflictResolutionResult {
        if (sessionOwnerUserId.isNotBlank()) {
            if (remote.ownerUserId.isNotBlank() && remote.ownerUserId != sessionOwnerUserId) {
                return ConflictResolutionResult.AccountMismatchRejected(sessionOwnerUserId, remote.ownerUserId)
            }
            if (local.ownerUserId.isNotBlank() && local.ownerUserId != sessionOwnerUserId) {
                return ConflictResolutionResult.AccountMismatchRejected(sessionOwnerUserId, local.ownerUserId)
            }
        }
        if (patch == null || patch.isEmpty) {
            return if (remote.revision > local.revision) {
                ConflictResolutionResult.AcceptedRemote(remote.copy(ownerUserId = sessionOwnerUserId))
            } else if (remote.revision == local.revision) {
                if (remote.updatedAtEpochMs > local.updatedAtEpochMs) {
                    ConflictResolutionResult.AcceptedRemote(remote.copy(ownerUserId = sessionOwnerUserId))
                } else {
                    ConflictResolutionResult.AcceptedRemote(local.copy(ownerUserId = sessionOwnerUserId))
                }
            } else {
                ConflictResolutionResult.Merged(
                    local.copy(ownerUserId = sessionOwnerUserId),
                    emptyList(),
                    true
                )
            }
        }
        val conflicts = mutableListOf<String>()
        val nextRevision = maxOf(local.revision, remote.revision) + 1L
        var merged = MultiDevicePreferences(
            ownerUserId = sessionOwnerUserId,
            revision = nextRevision,
            updatedAtEpochMs = now
        )
        for (i in 0 until 14) {
            val patchValue = patchField(patch, i)
            val remoteValue = prefsField(remote, i)
            val chosen = if (patchValue != null && patchValue != remoteValue) {
                conflicts.add(fieldNames[i])
                patchValue
            } else {
                remoteValue
            }
            merged = withPrefsField(merged, i, chosen)
        }
        return ConflictResolutionResult.Merged(merged, conflicts, true)
    }

    // ---- random input generation ----

    private val stringPool = listOf(
        "light", "dark", "system", "maodou", "x", "none", "red",
        "zh-cn", "en", "default", "pattern_1", "pattern_2", "normal", "large", ""
    )
    private val booleanPool = listOf(true, false)
    private val longPool = listOf(0L, 5L, 30L, 60L)
    private val revisions = listOf(0L, 1L, 2L, 3L, 4L, 5L, 100L, 1000L)
    private val timestamps = listOf(0L, 1L, 999L, 1000L, 1001L, 2000L, 5000L, Long.MAX_VALUE / 2)
    private val owners = listOf("", "u1", "u2")

    private fun randomFieldValue(r: Random, idx: Int): Any? = when (idx) {
        6, 7, 8, 12, 13 -> booleanPool[r.nextInt(booleanPool.size)]
        11 -> longPool[r.nextInt(longPool.size)]
        else -> stringPool[r.nextInt(stringPool.size)]
    }

    private fun randomPrefs(r: Random): MultiDevicePreferences {
        var p = MultiDevicePreferences(
            ownerUserId = owners[r.nextInt(owners.size)],
            revision = revisions[r.nextInt(revisions.size)],
            updatedAtEpochMs = timestamps[r.nextInt(timestamps.size)]
        )
        for (i in 0 until 14) {
            p = withPrefsField(p, i, randomFieldValue(r, i))
        }
        return p
    }

    private fun randomPatch(
        r: Random,
        local: MultiDevicePreferences,
        remote: MultiDevicePreferences
    ): PreferencesPatch? {
        if (r.nextInt(4) == 0) return null
        var patch = PreferencesPatch()
        for (i in 0 until 14) {
            if (r.nextInt(3) == 0) {
                val v = when (r.nextInt(3)) {
                    0 -> prefsField(local, i)
                    1 -> prefsField(remote, i)
                    else -> randomFieldValue(r, i)
                }
                patch = withPatchField(patch, i, v)
            }
        }
        return patch
    }

    // ---- tests ----

    @Test
    fun `random resolve matches oracle`() {
        val r = Random(3107)
        repeat(300) {
            val session = owners[r.nextInt(owners.size)]
            val local = randomPrefs(r)
            val remote = randomPrefs(r)
            val patch = randomPatch(r, local, remote)
            val now = timestamps[r.nextInt(timestamps.size)]
            assertEquals(
                oracleResolve(session, local, remote, patch, now),
                PreferenceConflictPolicy.resolve(session, local, remote, patch, now)
            )
        }
    }

    @Test
    fun `guard outcome matches isolation rule`() {
        val r = Random(3108)
        repeat(300) {
            val session = owners[r.nextInt(owners.size)]
            val local = randomPrefs(r)
            val remote = randomPrefs(r)
            val result = PreferenceConflictPolicy.resolve(session, local, remote, null, 5000L)
            val remoteMismatch = session.isNotBlank() &&
                remote.ownerUserId.isNotBlank() && remote.ownerUserId != session
            val localMismatch = session.isNotBlank() &&
                local.ownerUserId.isNotBlank() && local.ownerUserId != session
            if (remoteMismatch || localMismatch) {
                assertTrue(result is ConflictResolutionResult.AccountMismatchRejected)
                val rejected = result as ConflictResolutionResult.AccountMismatchRejected
                assertEquals(session, rejected.localOwner)
                assertEquals(
                    if (remoteMismatch) remote.ownerUserId else local.ownerUserId,
                    rejected.remoteOwner
                )
            } else {
                assertTrue(result !is ConflictResolutionResult.AccountMismatchRejected)
            }
        }
    }

    @Test
    fun `no patch branch follows revision then timestamp`() {
        val r = Random(3109)
        repeat(300) {
            val session = owners[r.nextInt(owners.size)]
            // Owners aligned with the session so the guard never fires.
            val local = randomPrefs(r).copy(ownerUserId = session)
            val remote = randomPrefs(r).copy(ownerUserId = session)
            val result = PreferenceConflictPolicy.resolve(session, local, remote, null, 5000L)
            when {
                remote.revision > local.revision -> {
                    assertTrue(result is ConflictResolutionResult.AcceptedRemote)
                    assertEquals(remote.copy(ownerUserId = session), (result as ConflictResolutionResult.AcceptedRemote).preferences)
                }
                remote.revision == local.revision && remote.updatedAtEpochMs > local.updatedAtEpochMs -> {
                    assertTrue(result is ConflictResolutionResult.AcceptedRemote)
                    assertEquals(remote.copy(ownerUserId = session), (result as ConflictResolutionResult.AcceptedRemote).preferences)
                }
                remote.revision == local.revision -> {
                    assertTrue(result is ConflictResolutionResult.AcceptedRemote)
                    assertEquals(local.copy(ownerUserId = session), (result as ConflictResolutionResult.AcceptedRemote).preferences)
                }
                else -> {
                    assertTrue(result is ConflictResolutionResult.Merged)
                    val merged = result as ConflictResolutionResult.Merged
                    assertEquals(local.copy(ownerUserId = session), merged.mergedPreferences)
                    assertTrue(merged.conflictFields.isEmpty())
                    assertTrue(merged.requiresPushToRemote)
                }
            }
        }
    }

    @Test
    fun `patch merge takes local intent on conflict and remote elsewhere`() {
        val r = Random(3110)
        repeat(300) {
            val session = owners[r.nextInt(owners.size)]
            val local = randomPrefs(r).copy(ownerUserId = session)
            val remote = randomPrefs(r).copy(ownerUserId = session)
            val patch = randomPatch(r, local, remote) ?: PreferencesPatch(themeMode = "dark")
            if (patch.isEmpty) return@repeat
            val now = timestamps[r.nextInt(timestamps.size)]
            val result = PreferenceConflictPolicy.resolve(session, local, remote, patch, now)
            assertTrue(result is ConflictResolutionResult.Merged)
            val merged = result as ConflictResolutionResult.Merged
            assertEquals(session, merged.mergedPreferences.ownerUserId)
            assertEquals(maxOf(local.revision, remote.revision) + 1L, merged.mergedPreferences.revision)
            assertEquals(now, merged.mergedPreferences.updatedAtEpochMs)
            assertTrue(merged.requiresPushToRemote)
            val expectedConflicts = mutableListOf<String>()
            for (i in 0 until 14) {
                val patchValue = patchField(patch, i)
                val remoteValue = prefsField(remote, i)
                val expected = if (patchValue != null && patchValue != remoteValue) {
                    expectedConflicts.add(fieldNames[i])
                    patchValue
                } else {
                    remoteValue
                }
                assertEquals(expected, prefsField(merged.mergedPreferences, i), "field ${fieldNames[i]}")
            }
            assertEquals(expectedConflicts, merged.conflictFields)
        }
    }

    @Test
    fun `revision boundaries pin max-plus-one behavior`() {
        val local = MultiDevicePreferences(ownerUserId = "u1", revision = Long.MAX_VALUE - 1)
        val remote = MultiDevicePreferences(ownerUserId = "u1", revision = Long.MAX_VALUE)
        val patch = PreferencesPatch(themeMode = "dark")
        val merged = PreferenceConflictPolicy.resolve("u1", local, remote, patch, 5000L)
        assertTrue(merged is ConflictResolutionResult.Merged)
        // max + 1 without overflow: Long.MAX_VALUE.
        assertEquals(Long.MAX_VALUE, (merged as ConflictResolutionResult.Merged).mergedPreferences.revision)
        // Both at Long.MAX_VALUE: max + 1 wraps (current behavior, pinned, not fixed here).
        val wrapped = PreferenceConflictPolicy.resolve(
            "u1",
            local.copy(revision = Long.MAX_VALUE),
            remote,
            patch,
            5000L
        )
        assertTrue(wrapped is ConflictResolutionResult.Merged)
        assertEquals(Long.MIN_VALUE, (wrapped as ConflictResolutionResult.Merged).mergedPreferences.revision)
        // Equal revision and equal timestamp keeps local.
        val tieLocal = MultiDevicePreferences(ownerUserId = "u1", revision = 3L, updatedAtEpochMs = 1000L, themeMode = "light")
        val tieRemote = MultiDevicePreferences(ownerUserId = "u1", revision = 3L, updatedAtEpochMs = 1000L, themeMode = "dark")
        val tie = PreferenceConflictPolicy.resolve("u1", tieLocal, tieRemote, null, 5000L)
        assertTrue(tie is ConflictResolutionResult.AcceptedRemote)
        assertEquals("light", (tie as ConflictResolutionResult.AcceptedRemote).preferences.themeMode)
    }

    @Test
    fun `empty patch behaves like null and resolve is deterministic`() {
        val r = Random(3111)
        repeat(100) {
            val session = owners[r.nextInt(owners.size)]
            val local = randomPrefs(r)
            val remote = randomPrefs(r)
            val viaNull = PreferenceConflictPolicy.resolve(session, local, remote, null, 5000L)
            val viaEmpty = PreferenceConflictPolicy.resolve(session, local, remote, PreferencesPatch(), 5000L)
            assertEquals(viaNull, viaEmpty)
            val again = PreferenceConflictPolicy.resolve(session, local, remote, null, 5000L)
            assertEquals(viaNull, again)
        }
    }
}
