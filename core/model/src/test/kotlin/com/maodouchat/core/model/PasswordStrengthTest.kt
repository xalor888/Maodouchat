package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthTest {

    @Test
    fun `blank password is weak with enter hint`() {
        val result = PasswordStrength.evaluate("   ")
        assertEquals(0, result.score)
        assertEquals(Level.WEAK, result.level)
        assertTrue(result.suggestions.contains(Suggestion.ENTER_PASSWORD))
    }

    @Test
    fun `full featured password is very strong`() {
        val result = PasswordStrength.evaluate("Abcdef12")
        assertEquals(4, result.score)
        assertEquals(Level.VERY_STRONG, result.level)
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `length plus lowercase is only fair`() {
        val result = PasswordStrength.evaluate("abcdefgh")
        assertEquals(2, result.score)
        assertEquals(Level.FAIR, result.level)
        assertTrue(result.suggestions.contains(Suggestion.ADD_DIGIT))
        assertTrue(result.suggestions.contains(Suggestion.ADD_UPPERCASE))
    }
}
