package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [SecretChatPolicy] (checklist line 931, 37th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the four rules from the documented behaviour:
 * a secret type is `SECRET` after trim + uppercase; only an ordinary direct
 * chat can start a secret chat; creating one needs exactly two people and
 * no group; a secret chat never belongs in a custom or locked folder. A
 * Python mini-model differentially checks the oracles on 5000 random
 * inputs per group (0 diffs) before this file was written.
 */
class SecretChatPolicyFuzzTest {

    private fun oracleIsSecretChatType(chatType: String?): Boolean =
        chatType?.trim()?.uppercase() == SecretChatPolicy.CHAT_TYPE

    private fun oracleCanStartFromDirect(isGroup: Boolean, chatType: String?): Boolean {
        if (isGroup) return false
        if (oracleIsSecretChatType(chatType)) return false
        val type = chatType?.trim()?.uppercase().orEmpty()
        return type.isEmpty() || type == "DIRECT"
    }

    private fun oracleCanCreateSecret(isGroup: Boolean, participantCount: Int): Boolean =
        !isGroup && participantCount == 2

    private fun oracleAllowInCustomOrLockedFolder(isSecret: Boolean): Boolean = !isSecret

    private fun randomChatType(rnd: Random): String? {
        val pool = listOf<String?>(
            null, "", " ", "   ", "SECRET", "secret", "SeCrEt", " secret ",
            "\tSECRET\n", "DIRECT", "direct", "Direct", " direct ",
            "GROUP", "group", "CHANNEL", "SECRETX", "SECRE", "SEC RET",
            "DIRECTX", "XSECRET", "S E C R E T", "SECRET ", " SECRET",
            "supergroup", "BOT"
        )
        return when (rnd.nextInt(5)) {
            0 -> pool.random(rnd)
            1 -> List(rnd.nextInt(0, 8)) { ('a' + rnd.nextInt(26)) }.joinToString("")
            2 -> listOf("SECRET", "DIRECT", "GROUP").random(rnd)
                .map { if (rnd.nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }
                .joinToString("").let { " $it " }
            3 -> List(rnd.nextInt(0, 10)) {
                listOf('S', 'E', 'C', 'R', 'T', 'D', 'I', 'X', ' ', '\t').random(rnd)
            }.joinToString("")
            else -> pool.random(rnd)
        }
    }

    private fun randomParticipantCount(rnd: Random): Int {
        val pool = listOf(-2, -1, 0, 1, 2, 3, 8, 100, Int.MIN_VALUE, Int.MAX_VALUE)
        return when (rnd.nextInt(3)) {
            0 -> pool.random(rnd)
            1 -> rnd.nextInt(-5, 10)
            else -> rnd.nextInt()
        }
    }

    @Test
    fun isSecretChatTypeRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val type = randomChatType(rnd)
            assertEquals(
                oracleIsSecretChatType(type),
                SecretChatPolicy.isSecretChatType(type),
                "chatType=<$type>"
            )
        }
    }

    @Test
    fun canStartFromDirectRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val isGroup = rnd.nextBoolean()
            val type = randomChatType(rnd)
            assertEquals(
                oracleCanStartFromDirect(isGroup, type),
                SecretChatPolicy.canStartFromDirect(isGroup, type),
                "isGroup=<$isGroup> chatType=<$type>"
            )
        }
    }

    @Test
    fun canCreateSecretAndFolderRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val isGroup = rnd.nextBoolean()
            val count = randomParticipantCount(rnd)
            val isSecret = rnd.nextBoolean()
            assertEquals(
                oracleCanCreateSecret(isGroup, count),
                SecretChatPolicy.canCreateSecret(isGroup, count),
                "isGroup=<$isGroup> count=<$count>"
            )
            assertEquals(
                oracleAllowInCustomOrLockedFolder(isSecret),
                SecretChatPolicy.allowInCustomOrLockedFolder(isSecret),
                "isSecret=<$isSecret>"
            )
        }
    }

    @Test
    fun pinnedEdges() {
        assertEquals("SECRET", SecretChatPolicy.CHAT_TYPE)
        assertTrue(SecretChatPolicy.isSecretChatType(" secret "))
        assertTrue(SecretChatPolicy.isSecretChatType("\tSECRET\n"))
        assertFalse(SecretChatPolicy.isSecretChatType("SEC RET"))
        assertFalse(SecretChatPolicy.isSecretChatType("SECRETX"))
        assertFalse(SecretChatPolicy.isSecretChatType("  "))
        assertTrue(SecretChatPolicy.canStartFromDirect(false, null))
        assertTrue(SecretChatPolicy.canStartFromDirect(false, ""))
        assertTrue(SecretChatPolicy.canStartFromDirect(false, "   "))
        assertTrue(SecretChatPolicy.canStartFromDirect(false, " direct "))
        assertFalse(SecretChatPolicy.canStartFromDirect(false, "DIRECTX"))
        assertFalse(SecretChatPolicy.canStartFromDirect(false, "secret"))
        assertFalse(SecretChatPolicy.canStartFromDirect(true, null))
        assertFalse(SecretChatPolicy.canStartFromDirect(true, "SECRET"))
        assertTrue(SecretChatPolicy.canCreateSecret(false, 2))
        assertFalse(SecretChatPolicy.canCreateSecret(false, 1))
        assertFalse(SecretChatPolicy.canCreateSecret(false, 3))
        assertFalse(SecretChatPolicy.canCreateSecret(false, -1))
        assertFalse(SecretChatPolicy.canCreateSecret(true, 2))
        assertTrue(SecretChatPolicy.allowInCustomOrLockedFolder(false))
        assertFalse(SecretChatPolicy.allowInCustomOrLockedFolder(true))
    }
}
