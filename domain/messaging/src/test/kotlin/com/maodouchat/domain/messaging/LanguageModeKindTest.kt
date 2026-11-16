package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageModeKindTest {

    @Test
    fun localChinese() {
        assertEquals(LanguageModeKind.CHINESE, languageModeKind("zh"))
    }

    @Test
    fun localEnglish() {
        assertEquals(LanguageModeKind.ENGLISH, languageModeKind("en"))
    }

    @Test
    fun localSystem() {
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind("system"))
    }

    @Test
    fun localUnknownFallsBackToSystem() {
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind("fr"))
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind(""))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(LanguageModeKind.CHINESE, languageModeKind("ZH"))
        assertEquals(LanguageModeKind.ENGLISH, languageModeKind("En"))
    }

    @Test
    fun localAliasesStaySystem() {
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind("zh-cn"))
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind("chinese"))
        assertEquals(LanguageModeKind.SYSTEM, languageModeKind("english"))
    }

    @Test
    fun remoteChineseAliases() {
        assertEquals(LanguageModeKind.CHINESE, languageModeKind("zh-cn", allowAliases = true))
        assertEquals(LanguageModeKind.CHINESE, languageModeKind("chinese", allowAliases = true))
    }

    @Test
    fun remoteEnglishAlias() {
        assertEquals(LanguageModeKind.ENGLISH, languageModeKind("english", allowAliases = true))
    }
}
