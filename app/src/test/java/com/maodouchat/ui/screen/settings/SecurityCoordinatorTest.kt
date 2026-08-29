package com.maodouchat.ui.screen.settings

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SecurityCoordinatorTest {
    @Test
    fun emptyPatchIsRejectedWithoutRepositoryCall() = runTest {
        var saves = 0
        val session = SettingsSession("owner")
        val repository = fakeRepository(session) { saves++ }

        val result = SecurityCoordinator(repository).push(session, SecurityPreferencesPatch())

        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertEquals(0, saves)
    }

    @Test
    fun changedSessionRejectsLoadedPreferences() = runTest {
        val session = SettingsSession("owner")
        var current = true
        val repository = object : SettingsRepository by fakeRepository(session) {
            override fun isCurrent(session: SettingsSession) = current
            override suspend fun loadSecurityPreferences(session: SettingsSession): Result<SecurityPreferences> {
                current = false
                return Result.success(SecurityPreferences(5, false, true))
            }
        }

        val result = SecurityCoordinator(repository).pull(session)

        assertIs<SettingsSessionChangedException>(result.exceptionOrNull())
    }

    private fun fakeRepository(
        session: SettingsSession,
        onSave: () -> Unit = {},
    ): SettingsRepository = object : SettingsRepository {
        override fun currentSession() = session
        override fun isCurrent(session: SettingsSession) = true
        override suspend fun loadProfile(session: SettingsSession) = Result.failure<SettingsProfile>(UnsupportedOperationException())
        override suspend fun loadPrivacy(session: SettingsSession) = Result.failure<SettingsPrivacy>(UnsupportedOperationException())
        override suspend fun savePrivacy(session: SettingsSession, privacy: SettingsPrivacyPatch) = Result.failure<SettingsPrivacy>(UnsupportedOperationException())
        override suspend fun loadSecurityPreferences(session: SettingsSession) = Result.success(SecurityPreferences(5, false, true))
        override suspend fun saveSecurityPreferences(
            session: SettingsSession,
            patch: SecurityPreferencesPatch,
        ): Result<SecurityPreferences> {
            onSave()
            return Result.success(SecurityPreferences(5, false, true))
        }
    }
}
