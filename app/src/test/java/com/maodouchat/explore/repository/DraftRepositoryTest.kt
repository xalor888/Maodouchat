package com.maodouchat.explore.repository

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DraftRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var draftRepo: DraftRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        draftRepo = SharedPrefsDraftRepository(fakePrefs)
    }

    @Test
    fun accountIsolation_userADraftNotVisibleToUserB() {
        draftRepo.saveComposerText("userA", "Draft from Alice")
        draftRepo.saveVisibility("userA", "PUBLIC")
        draftRepo.saveImageUris("userA", listOf("content://img1", "content://img2"))

        // User B should see blank / default values
        val draftB = draftRepo.getDraft("userB", defaultVisibility = "CONTACTS")
        assertEquals("", draftB.composerText)
        assertEquals("CONTACTS", draftB.selectedVisibility)
        assertTrue(draftB.imageUris.isEmpty())

        // User A reads back their draft
        val draftA = draftRepo.getDraft("userA")
        assertEquals("Draft from Alice", draftA.composerText)
        assertEquals("PUBLIC", draftA.selectedVisibility)
        assertEquals(listOf("content://img1", "content://img2"), draftA.imageUris)
    }

    @Test
    fun clearComposerDraft_removesTextAndImages_keepsVisibility() {
        draftRepo.saveComposerText("userA", "Hello World")
        draftRepo.saveVisibility("userA", "CONTACTS")
        draftRepo.saveImageUris("userA", listOf("content://img1"))

        draftRepo.clearComposerDraft("userA")

        val draft = draftRepo.getDraft("userA")
        assertEquals("", draft.composerText)
        assertTrue(draft.imageUris.isEmpty())
        assertEquals("CONTACTS", draft.selectedVisibility)
    }

    @Test
    fun clearAllDrafts_removesEverythingForUser() {
        draftRepo.saveComposerText("userA", "Hello World")
        draftRepo.saveVisibility("userA", "PUBLIC")
        draftRepo.saveImageUris("userA", listOf("content://img1"))

        draftRepo.clearAllDrafts("userA")

        val draft = draftRepo.getDraft("userA", defaultVisibility = "PRIVATE")
        assertEquals("", draft.composerText)
        assertEquals("PRIVATE", draft.selectedVisibility)
        assertTrue(draft.imageUris.isEmpty())
    }

    @Test
    fun legacyMigration_migratesOnceToFirstOwner() {
        fakePrefs.putStringDirect("composer_text", "Old legacy draft")
        fakePrefs.putStringDirect("selected_visibility", "PUBLIC")

        // First user logs in and triggers migration
        val draftA = draftRepo.getDraft("userA")
        assertEquals("Old legacy draft", draftA.composerText)
        assertEquals("PUBLIC", draftA.selectedVisibility)

        // Legacy keys should be marked migrated and cleared
        val draftB = draftRepo.getDraft("userB")
        assertEquals("", draftB.composerText)
    }

    @Test
    fun blankUserId_failsSafe() {
        draftRepo.saveComposerText("", "Should not save")
        val draft = draftRepo.getDraft("")
        assertEquals("", draft.composerText)
        assertEquals("PRIVATE", draft.selectedVisibility)
    }

    /**
     * Minimal in-memory fake for SharedPreferences.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        fun putStringDirect(key: String, value: String) {
            map[key] = value
        }

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val backingMap: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) {
                    backingMap.clear()
                }
                pending.forEach { (k, v) ->
                    if (v == null) {
                        backingMap.remove(k)
                    } else {
                        backingMap[k] = v
                    }
                }
                pending.clear()
            }
        }
    }
}
