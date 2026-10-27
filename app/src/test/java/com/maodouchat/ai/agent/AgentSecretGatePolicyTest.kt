package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSecretGatePolicyTest {

    @Test
    fun secretAlwaysDeniedEvenIfUnlocked() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = false, unlocked = true)
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = true, isLocked = false, unlocked = true))
    }

    @Test
    fun pinLockedDeniedUntilUnlocked() {
        assertEquals(
            AgentSecretGatePolicy.PIN_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = false)
        )
        assertNull(
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = true)
        )
        assertTrue(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = true))
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = false, isLocked = true, unlocked = false))
    }

    @Test
    fun ordinaryChatAllowed() {
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, false))
        assertTrue(AgentSecretGatePolicy.includeInChatList(false, false, false))
    }

    @Test
    fun secretDenialBeatsPinDenial() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = true, unlocked = true)
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(isSecret = true, isLocked = true, unlocked = true))
    }

    @Test
    fun denialAndListingAgreeAcrossAllEightCombinations() {
        for (isSecret in listOf(false, true)) {
            for (isLocked in listOf(false, true)) {
                for (unlocked in listOf(false, true)) {
                    val denied = AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret, isLocked, unlocked) != null
                    val listed = AgentSecretGatePolicy.includeInChatList(isSecret, isLocked, unlocked)
                    assertEquals("($isSecret, $isLocked, $unlocked)", !listed, denied)
                }
            }
        }
    }

    @Test
    fun denialConstantsAreDistinctAndNonBlank() {
        assertNotEquals(AgentSecretGatePolicy.SECRET_DENIED, AgentSecretGatePolicy.PIN_DENIED)
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.isNotBlank())
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.isNotBlank())
    }

    @Test
    fun decisionsAreRepeatable() {
        repeat(3) {
            assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, false))
            assertEquals(
                AgentSecretGatePolicy.SECRET_DENIED,
                AgentSecretGatePolicy.denyIfSecretOrLocked(true, false, false)
            )
        }
    }

    @Test
    fun unlockedFlagAloneNeverDenies() {
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, true))
        assertTrue(AgentSecretGatePolicy.includeInChatList(false, false, true))
    }

    @Test
    fun denialConstantsAreExactLiterals() {
        assertEquals(
            "Error: secret chats are not available to the assistant",
            AgentSecretGatePolicy.SECRET_DENIED
        )
        assertEquals("Error: chat is PIN-locked", AgentSecretGatePolicy.PIN_DENIED)
    }

    @Test
    fun denialConstantsCarryErrorPrefix() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.startsWith("Error: "))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.startsWith("Error: "))
    }

    @Test
    fun secretDenialIgnoresUnlockedFlag() {
        for (unlocked in listOf(false, true)) {
            assertEquals(
                AgentSecretGatePolicy.SECRET_DENIED,
                AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = false, unlocked = unlocked)
            )
            assertFalse(AgentSecretGatePolicy.includeInChatList(true, false, unlocked))
        }
    }

    @Test
    fun pinDenialNeedsLockedAndNotUnlockedTogether() {
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, false))
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, false, true))
        assertNull(AgentSecretGatePolicy.denyIfSecretOrLocked(false, true, true))
        assertEquals(
            AgentSecretGatePolicy.PIN_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(false, true, false)
        )
    }

    @Test
    fun secretDenialBeatsPinDenialWhenBothApply() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = true, unlocked = false)
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(true, true, false))
    }

    @Test
    fun denialTablePinsEveryCombinationExplicitly() {
        val cases = listOf(
            Triple(false, false, false) to null,
            Triple(false, false, true) to null,
            Triple(false, true, false) to AgentSecretGatePolicy.PIN_DENIED,
            Triple(false, true, true) to null,
            Triple(true, false, false) to AgentSecretGatePolicy.SECRET_DENIED,
            Triple(true, false, true) to AgentSecretGatePolicy.SECRET_DENIED,
            Triple(true, true, false) to AgentSecretGatePolicy.SECRET_DENIED,
            Triple(true, true, true) to AgentSecretGatePolicy.SECRET_DENIED
        )
        for ((input, expected) in cases) {
            assertEquals(
                "$input",
                expected,
                AgentSecretGatePolicy.denyIfSecretOrLocked(input.first, input.second, input.third)
            )
        }
    }

    @Test
    fun listingTablePinsEveryCombinationExplicitly() {
        val cases = listOf(
            Triple(false, false, false) to true,
            Triple(false, false, true) to true,
            Triple(false, true, false) to false,
            Triple(false, true, true) to true,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to false,
            Triple(true, true, true) to false
        )
        for ((input, expected) in cases) {
            assertEquals(
                "$input",
                expected,
                AgentSecretGatePolicy.includeInChatList(input.first, input.second, input.third)
            )
        }
    }

    @Test
    fun denialMessagesNameTheirOwnGate() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.contains("secret"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("PIN"))
    }

    @Test
    fun denialMessagesNeverNameTheOtherGate() {
        assertFalse(AgentSecretGatePolicy.SECRET_DENIED.contains("PIN"))
        assertFalse(AgentSecretGatePolicy.PIN_DENIED.contains("secret"))
    }

    @Test
    fun denialMessagesAreSingleLineWithoutTrailingWhitespace() {
        listOf(AgentSecretGatePolicy.SECRET_DENIED, AgentSecretGatePolicy.PIN_DENIED).forEach { message ->
            assertFalse(message.contains("\n"))
            assertEquals(message.trim(), message)
        }
    }

    @Test
    fun secretDenialMessageIsLongerThanPinDenialMessage() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.length > AgentSecretGatePolicy.PIN_DENIED.length)
    }

    @Test
    fun denialMessagesNameTheirOwnSubject() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.contains("assistant"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("chat"))
    }

    @Test
    fun denialMessagesArePureAscii() {
        listOf(AgentSecretGatePolicy.SECRET_DENIED, AgentSecretGatePolicy.PIN_DENIED).forEach { message ->
            assertTrue(message.all { it.code < 128 })
        }
    }

    @Test
    fun denialMessagesStateTheirOwnReason() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.contains("not available"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("locked"))
    }

    @Test
    fun denialResultDomainIsOnlyNullOrTheTwoConstants() {
        val allowed = setOf(
            null,
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.PIN_DENIED
        )
        for (isSecret in listOf(false, true)) {
            for (isLocked in listOf(false, true)) {
                for (unlocked in listOf(false, true)) {
                    assertTrue(
                        allowed.contains(
                            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret, isLocked, unlocked)
                        )
                    )
                }
            }
        }
    }

    @Test
    fun denialConstantsShareTheSameErrorPrefix() {
        assertEquals("Error: ", AgentSecretGatePolicy.SECRET_DENIED.take(7))
        assertEquals("Error: ", AgentSecretGatePolicy.PIN_DENIED.take(7))
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED.take(7),
            AgentSecretGatePolicy.PIN_DENIED.take(7)
        )
    }

    @Test
    fun denialMessagesUppercaseShape() {
        assertEquals(
            "E",
            AgentSecretGatePolicy.SECRET_DENIED.filter { it.isUpperCase() }
        )
        assertEquals(
            "EPIN",
            AgentSecretGatePolicy.PIN_DENIED.filter { it.isUpperCase() }
        )
    }

    @Test
    fun denialConstantsHaveExactLengths() {
        assertEquals(54, AgentSecretGatePolicy.SECRET_DENIED.length)
        assertEquals(25, AgentSecretGatePolicy.PIN_DENIED.length)
    }

    @Test
    fun namedArgumentsInAnyOrderGiveTheSameDecision() {
        assertNull(
            AgentSecretGatePolicy.denyIfSecretOrLocked(
                unlocked = true,
                isLocked = true,
                isSecret = false
            )
        )
        assertEquals(
            AgentSecretGatePolicy.PIN_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(
                unlocked = false,
                isLocked = true,
                isSecret = false
            )
        )
    }

    @Test
    fun repeatedPinDenialIsStable() {
        repeat(5) {
            assertEquals(
                AgentSecretGatePolicy.PIN_DENIED,
                AgentSecretGatePolicy.denyIfSecretOrLocked(false, true, false)
            )
            assertFalse(AgentSecretGatePolicy.includeInChatList(false, true, false))
        }
    }
    @Test
    fun denialMessagesEndWithTheirSubject() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.endsWith("assistant"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.endsWith("locked"))
    }

    @Test
    fun denialMessagesContainExactlyOneColon() {
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.count { it == ':' })
        assertEquals(1, AgentSecretGatePolicy.PIN_DENIED.count { it == ':' })
    }

    @Test
    fun denialMessagesContainNoDigits() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.none { it.isDigit() })
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.none { it.isDigit() })
    }

    @Test
    fun positionalAndNamedCallsAgree() {
        assertEquals(
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = false, unlocked = true),
            AgentSecretGatePolicy.denyIfSecretOrLocked(true, false, true)
        )
        assertEquals(
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = false, isLocked = true, unlocked = false),
            AgentSecretGatePolicy.denyIfSecretOrLocked(false, true, false)
        )
    }

    @Test
    fun denialMessagesHaveExactWordCounts() {
        assertEquals(9, AgentSecretGatePolicy.SECRET_DENIED.split(" ").size)
        assertEquals(4, AgentSecretGatePolicy.PIN_DENIED.split(" ").size)
    }

    @Test
    fun denialMessagesStartWithOwnSubjectPhrase() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.startsWith("Error: secret chats"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.startsWith("Error: chat is PIN"))
    }

    @Test
    fun denialMessagesDifferOnPluralChatWord() {
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.contains("chats"))
        assertFalse(AgentSecretGatePolicy.PIN_DENIED.contains("chats"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("chat "))
    }

    @Test
    fun denialMessagesLengthParityAndSum() {
        assertEquals(0, AgentSecretGatePolicy.SECRET_DENIED.length % 2)
        assertEquals(1, AgentSecretGatePolicy.PIN_DENIED.length % 2)
        assertEquals(
            79,
            AgentSecretGatePolicy.SECRET_DENIED.length + AgentSecretGatePolicy.PIN_DENIED.length
        )
    }

    @Test
    fun denialMessagesEndWithLettersAndHyphenOnlyInPin() {
        assertFalse(AgentSecretGatePolicy.SECRET_DENIED.contains("-"))
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("-"))
        assertTrue(AgentSecretGatePolicy.SECRET_DENIED.last().isLetter())
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.last().isLetter())
    }

    @Test
    fun denialMessagesFirstTokenIsErrorAndSecondNamesSubject() {
        assertEquals("Error:", AgentSecretGatePolicy.SECRET_DENIED.split(" ")[0])
        assertEquals("Error:", AgentSecretGatePolicy.PIN_DENIED.split(" ")[0])
        assertEquals("secret", AgentSecretGatePolicy.SECRET_DENIED.split(" ")[1])
        assertEquals("chat", AgentSecretGatePolicy.PIN_DENIED.split(" ")[1])
    }

    @Test
    fun denialMessagesContainNoQuestionOrExclamation() {
        listOf(AgentSecretGatePolicy.SECRET_DENIED, AgentSecretGatePolicy.PIN_DENIED).forEach { message ->
            assertFalse(message.contains("?"))
            assertFalse(message.contains("!"))
        }
    }

    @Test
    fun secretMessageCountsAvailableAndAssistantOnce() {
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("available").size - 1)
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("assistant").size - 1)
    }

    @Test
    fun pinDenialBodyHasThreeWords() {
        assertEquals(
            listOf("chat", "is", "PIN-locked"),
            AgentSecretGatePolicy.PIN_DENIED.removePrefix("Error: ").split(" ")
        )
    }

    @Test
    fun secretDenialForEveryLockedCombination() {
        for (isLocked in listOf(false, true)) {
            for (unlocked in listOf(false, true)) {
                assertEquals(
                    AgentSecretGatePolicy.SECRET_DENIED,
                    AgentSecretGatePolicy.denyIfSecretOrLocked(true, isLocked, unlocked)
                )
            }
        }
    }

    @Test
    fun secretChatsNeverGetPinDenial() {
        for (isLocked in listOf(false, true)) {
            for (unlocked in listOf(false, true)) {
                assertNotEquals(
                    AgentSecretGatePolicy.PIN_DENIED,
                    AgentSecretGatePolicy.denyIfSecretOrLocked(true, isLocked, unlocked)
                )
            }
        }
    }

    @Test
    fun secretDenialWordListIsExact() {
        assertEquals(
            listOf("Error:", "secret", "chats", "are", "not", "available", "to", "the", "assistant"),
            AgentSecretGatePolicy.SECRET_DENIED.split(" ")
        )
    }

    @Test
    fun pinDenialWordListIsExact() {
        assertEquals(
            listOf("Error:", "chat", "is", "PIN-locked"),
            AgentSecretGatePolicy.PIN_DENIED.split(" ")
        )
    }

    @Test
    fun deniedExactlyWhenSecretOrLockedAndNotUnlocked() {
        for (isSecret in listOf(false, true)) {
            for (isLocked in listOf(false, true)) {
                for (unlocked in listOf(false, true)) {
                    assertEquals(
                        "($isSecret, $isLocked, $unlocked)",
                        isSecret || (isLocked && !unlocked),
                        AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret, isLocked, unlocked) != null
                    )
                }
            }
        }
    }

    @Test
    fun secretDenialSmallWordsAppearOnce() {
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("the").size - 1)
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("are").size - 1)
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("not").size - 1)
    }

    @Test
    fun secretChatsExcludedFromListingInEveryLockedState() {
        for (isLocked in listOf(false, true)) {
            for (unlocked in listOf(false, true)) {
                assertFalse(AgentSecretGatePolicy.includeInChatList(true, isLocked, unlocked))
            }
        }
    }

    @Test
    fun secretDenialBeatsPinDenialWhenLockedAndNotUnlocked() {
        assertEquals(
            AgentSecretGatePolicy.SECRET_DENIED,
            AgentSecretGatePolicy.denyIfSecretOrLocked(isSecret = true, isLocked = true, unlocked = false)
        )
        assertFalse(AgentSecretGatePolicy.includeInChatList(true, true, false))
    }

    @Test
    fun pinDenialMentionsPinExactlyOnce() {
        assertEquals(1, AgentSecretGatePolicy.PIN_DENIED.split("PIN").size - 1)
        assertTrue(AgentSecretGatePolicy.PIN_DENIED.contains("PIN-locked"))
    }

    @Test
    fun secretDenialMentionsSecretAndAssistantOnceEach() {
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("secret").size - 1)
        assertEquals(1, AgentSecretGatePolicy.SECRET_DENIED.split("assistant").size - 1)
    }

    @Test
    fun denialStringsAreSingleLineAndUnpadded() {
        assertEquals(AgentSecretGatePolicy.SECRET_DENIED, AgentSecretGatePolicy.SECRET_DENIED.trim())
        assertEquals(AgentSecretGatePolicy.PIN_DENIED, AgentSecretGatePolicy.PIN_DENIED.trim())
        assertFalse(AgentSecretGatePolicy.SECRET_DENIED.contains("\n"))
        assertFalse(AgentSecretGatePolicy.PIN_DENIED.contains("\n"))
    }

    @Test
    fun exactlyThreeOfEightCombinationsAreListed() {
        var listed = 0
        var denied = 0
        for (isSecret in listOf(false, true)) {
            for (isLocked in listOf(false, true)) {
                for (unlocked in listOf(false, true)) {
                    if (AgentSecretGatePolicy.includeInChatList(isSecret, isLocked, unlocked)) listed++ else denied++
                }
            }
        }
        assertEquals(3, listed)
        assertEquals(5, denied)
    }

}
