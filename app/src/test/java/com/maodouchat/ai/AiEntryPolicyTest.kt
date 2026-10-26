package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEntryPolicyTest {
    @Test
    fun `composer section order is draft chat settings`() {
        assertEquals(
            listOf(
                ComposerSection.DRAFT,
                ComposerSection.CHAT,
                ComposerSection.SETTINGS
            ),
            AiEntryPolicy.COMPOSER_SECTION_ORDER
        )
        assertEquals("composer_menu", AiEntryPolicy.PRIMARY_SURFACE)
        assertEquals("message_actions", AiEntryPolicy.CONTEXT_SURFACE)
        assertEquals("ai_privacy", AiEntryPolicy.SETTINGS_SURFACE)
    }

    @Test
    fun `context actions by type`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("TEXT")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("VOICE", hasTranscript = false)
        )
        assertTrue(AiEntryPolicy.contextActionsFor("VOICE", hasTranscript = true).isEmpty())
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("IMAGE")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("FILE")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("STICKER").isEmpty())
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor(" system ")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("text")
        )
    }

    @Test
    fun `composer and run gates`() {
        assertTrue(AiEntryPolicy.isComposerEntryActive(true))
        assertFalse(AiEntryPolicy.isComposerEntryActive(false))
        assertTrue(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = false))
        assertFalse(AiEntryPolicy.canRunContextAction(chatAiEnabled = false, isBusy = false))
        assertFalse(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = true))
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = false,
                chatAiEnabled = true,
                isBusy = false
            )
        )
        assertTrue(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = false))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = true))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
    }

    @Test
    fun `ai surfaces stay hidden until settings are on`() {
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = false
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = false,
                userEnabled = true
            )
        )
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = false,
                consentAccepted = true,
                userEnabled = true
            )
        )
        assertTrue(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = true
            )
        )
    }

    @Test
    fun `hasContextAiActions mirrors contextActionsFor`() {
        assertTrue(AiEntryPolicy.hasContextAiActions("MARKDOWN"))
        assertTrue(AiEntryPolicy.hasContextAiActions("gif"))
        assertFalse(AiEntryPolicy.hasContextAiActions(null))
        assertFalse(AiEntryPolicy.hasContextAiActions("POLL"))
    }

    @Test
    fun `masterEnabled false blocks every surface`() {
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = true,
                masterEnabled = false
            )
        )
        // userEnabled defaults to false: surfaces stay hidden without opt-in.
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true
            )
        )
    }

    @Test
    fun `composer menu blocks on busy and on setting update`() {
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = true, isUpdatingSetting = true))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
        assertTrue(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = false))
    }

    @Test
    fun `composer section order is frozen`() {
        assertEquals(3, AiEntryPolicy.COMPOSER_SECTION_ORDER.size)
        assertEquals(ComposerSection.DRAFT, AiEntryPolicy.COMPOSER_SECTION_ORDER.first())
        assertEquals(ComposerSection.SETTINGS, AiEntryPolicy.COMPOSER_SECTION_ORDER.last())
        assertEquals(3, AiEntryPolicy.COMPOSER_SECTION_ORDER.distinct().size)
    }

    @Test
    fun `unknown and blank types get no actions`() {
        assertTrue(AiEntryPolicy.contextActionsFor("POLL").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor(null).isEmpty())
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("MARKDOWN")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor(" gif ")
        )
    }

    @Test
    fun `context action type casing and whitespace fold to canonical lists`() {
        assertEquals(
            listOf(MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("  vOiCe  ")
        )
        assertTrue(
            AiEntryPolicy.contextActionsFor("  vOiCe  ", hasTranscript = true).isEmpty()
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("image")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("File ")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("VIDEO").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor(" audio ").isEmpty())
    }

    @Test
    fun `transcript flag only affects voice`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("TEXT", hasTranscript = true)
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("IMAGE", hasTranscript = true)
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("FILE", hasTranscript = true)
        )
        assertTrue(AiEntryPolicy.hasContextAiActions("TEXT", hasTranscript = true))
    }

    @Test
    fun `composer menu default leaves setting update unset`() {
        assertTrue(AiEntryPolicy.canOpenComposerMenu(false))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(true))
        assertTrue(AiEntryPolicy.canOpenComposerMenu(isBusy = false))
    }

    @Test
    fun `hasContextAiActions follows the voice transcript flag`() {
        assertTrue(AiEntryPolicy.hasContextAiActions("VOICE", hasTranscript = false))
        assertFalse(AiEntryPolicy.hasContextAiActions("VOICE", hasTranscript = true))
        assertTrue(AiEntryPolicy.hasContextAiActions("FILE"))
        assertTrue(AiEntryPolicy.hasContextAiActions("IMAGE"))
    }

    @Test
    fun `whitespace variants fold before type matching`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("\ttext\n")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("  GIF\r\n")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("\n\t ").isEmpty())
    }

    @Test
    fun `surface constants are distinct and non-blank`() {
        val surfaces = setOf(
            AiEntryPolicy.PRIMARY_SURFACE,
            AiEntryPolicy.CONTEXT_SURFACE,
            AiEntryPolicy.SETTINGS_SURFACE
        )
        assertEquals(3, surfaces.size)
        assertTrue(surfaces.all { it.isNotBlank() })
    }

    @Test
    fun `composer section order covers exactly the enum entries`() {
        assertEquals(
            ComposerSection.entries.toSet(),
            AiEntryPolicy.COMPOSER_SECTION_ORDER.toSet()
        )
        assertEquals(
            ComposerSection.entries.size,
            AiEntryPolicy.COMPOSER_SECTION_ORDER.size
        )
    }

    @Test
    fun `two-arg run gate delegates to the master-enabled form`() {
        for (enabled in listOf(true, false)) {
            for (busy in listOf(true, false)) {
                assertEquals(
                    AiEntryPolicy.canRunContextAction(
                        masterEnabled = true,
                        chatAiEnabled = enabled,
                        isBusy = busy
                    ),
                    AiEntryPolicy.canRunContextAction(
                        chatAiEnabled = enabled,
                        isBusy = busy
                    )
                )
            }
        }
    }

    @Test
    fun `hasContextAiActions mirrors contextActionsFor across types`() {
        val types = listOf("TEXT", " voice ", "IMAGE", "file", "MARKDOWN", "SYSTEM", "POLL", null, "  ")
        for (type in types) {
            assertEquals(
                AiEntryPolicy.contextActionsFor(type).isNotEmpty(),
                AiEntryPolicy.hasContextAiActions(type)
            )
            assertEquals(
                AiEntryPolicy.contextActionsFor(type, hasTranscript = true).isNotEmpty(),
                AiEntryPolicy.hasContextAiActions(type, hasTranscript = true)
            )
        }
    }

    @Test
    fun `three-arg run gate completes the denial directions`() {
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = false,
                chatAiEnabled = false,
                isBusy = false
            )
        )
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = true,
                chatAiEnabled = false,
                isBusy = true
            )
        )
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = false,
                chatAiEnabled = true,
                isBusy = true
            )
        )
        assertFalse(
            AiEntryPolicy.canRunContextAction(
                masterEnabled = true,
                chatAiEnabled = true,
                isBusy = true
            )
        )
    }

    @Test
    fun `shouldShowAiSurfaces is exactly the four-way conjunction`() {
        for (master in listOf(true, false)) {
            for (user in listOf(true, false)) {
                for (consent in listOf(true, false)) {
                    for (chat in listOf(true, false)) {
                        assertEquals(
                            master && user && consent && chat,
                            AiEntryPolicy.shouldShowAiSurfaces(
                                chatAiEnabled = chat,
                                consentAccepted = consent,
                                userEnabled = user,
                                masterEnabled = master
                            )
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `three-arg run gate is exactly master and chat and not busy`() {
        for (master in listOf(true, false)) {
            for (chat in listOf(true, false)) {
                for (busy in listOf(true, false)) {
                    assertEquals(
                        master && chat && !busy,
                        AiEntryPolicy.canRunContextAction(
                            masterEnabled = master,
                            chatAiEnabled = chat,
                            isBusy = busy
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `mapped type families collapse to one action each`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("TEXT")
        )
        assertEquals(
            AiEntryPolicy.contextActionsFor("TEXT"),
            AiEntryPolicy.contextActionsFor("MARKDOWN")
        )
        assertEquals(
            AiEntryPolicy.contextActionsFor("TEXT"),
            AiEntryPolicy.contextActionsFor("SYSTEM")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("IMAGE")
        )
        assertEquals(
            AiEntryPolicy.contextActionsFor("IMAGE"),
            AiEntryPolicy.contextActionsFor("GIF")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("VOICE")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("FILE")
        )
    }

    @Test
    fun `two-arg run gate is exactly chat and not busy`() {
        for (chat in listOf(true, false)) {
            for (busy in listOf(true, false)) {
                assertEquals(
                    chat && !busy,
                    AiEntryPolicy.canRunContextAction(
                        chatAiEnabled = chat,
                        isBusy = busy
                    )
                )
            }
        }
    }

    @Test
    fun `canOpenComposerMenu is exactly neither blocker`() {
        for (busy in listOf(true, false)) {
            for (updating in listOf(true, false)) {
                assertEquals(
                    !busy && !updating,
                    AiEntryPolicy.canOpenComposerMenu(
                        isBusy = busy,
                        isUpdatingSetting = updating
                    )
                )
            }
        }
    }

    @Test
    fun `transcript flag leaves non-voice actions unchanged`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("SYSTEM", hasTranscript = true)
        )
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("MARKDOWN", hasTranscript = true)
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("GIF", hasTranscript = true)
        )
        assertTrue(AiEntryPolicy.hasContextAiActions("SYSTEM", hasTranscript = true))
        assertTrue(AiEntryPolicy.hasContextAiActions("MARKDOWN", hasTranscript = true))
        assertTrue(AiEntryPolicy.hasContextAiActions("GIF", hasTranscript = true))
    }

    @Test
    fun `internal whitespace never folds to a known type`() {
        assertTrue(AiEntryPolicy.contextActionsFor("T EXT").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("VO ICE").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("IM AGE").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("FI LE").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT TEXT").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT\tTEXT").isEmpty())
    }

    @Test
    fun `prefix and suffix variants match nothing`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TEXTS").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT2").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("IMAGES").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("VOICES").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("FILEX").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT-FILE").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("TEXTS"))
    }

    @Test
    fun `non-breaking space folds before type matching`() {
        // Kotlin trim 会剥离 U+00A0，所以 NBSP 前后缀不影响类型匹配。
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("\u00A0TEXT")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("TEXT\u00A0")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("\u00A0").isEmpty())
    }

    @Test
    fun `non-voice actions ignore the transcript flag`() {
        val types = listOf("TEXT", "MARKDOWN", "SYSTEM", "IMAGE", "GIF", "FILE", "POLL", null, "  ")
        for (type in types) {
            assertEquals(
                AiEntryPolicy.contextActionsFor(type, hasTranscript = false),
                AiEntryPolicy.contextActionsFor(type, hasTranscript = true)
            )
        }
    }

    @Test
    fun `hasContextAiActions voice default follows no transcript form`() {
        assertTrue(AiEntryPolicy.hasContextAiActions("VOICE"))
        assertEquals(
            AiEntryPolicy.hasContextAiActions("VOICE", hasTranscript = false),
            AiEntryPolicy.hasContextAiActions("VOICE")
        )
    }

    @Test
    fun `hasContextAiActions empty string has no actions`() {
        assertFalse(AiEntryPolicy.hasContextAiActions(""))
        assertEquals(
            AiEntryPolicy.contextActionsFor("").isNotEmpty(),
            AiEntryPolicy.hasContextAiActions("")
        )
    }

    @Test
    fun `contextActionsFor tab wrapped voice folds to transcribe`() {
        assertEquals(
            listOf(MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("\tvoice\n")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("\tvoice\n", hasTranscript = true).isEmpty())
    }

    @Test
    fun `contextActionsFor lowercase system with transcript still translates`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("system", hasTranscript = true)
        )
    }

    @Test
    fun `voice default action list equals explicit no transcript list`() {
        assertEquals(
            AiEntryPolicy.contextActionsFor("VOICE", hasTranscript = false),
            AiEntryPolicy.contextActionsFor("VOICE")
        )
    }


    @Test
    fun `mixed case gif file and system fold to their actions`() {
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("gIf")
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("fIlE")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("sYsTeM")
        )
    }

    @Test
    fun `tab wrapped file transcribes nothing and padded voice with transcript is empty`() {
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("\tfile\n")
        )
        assertTrue(AiEntryPolicy.contextActionsFor(" voice ", hasTranscript = true).isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions(" voice ", hasTranscript = true))
    }

    @Test
    fun `every known type maps to exactly one action`() {
        val cases = mapOf(
            "TEXT" to MessageAiAction.TRANSLATE,
            "MARKDOWN" to MessageAiAction.TRANSLATE,
            "SYSTEM" to MessageAiAction.TRANSLATE,
            "VOICE" to MessageAiAction.TRANSCRIBE,
            "IMAGE" to MessageAiAction.ANALYZE_IMAGE,
            "GIF" to MessageAiAction.ANALYZE_IMAGE,
            "FILE" to MessageAiAction.ANALYZE_FILE
        )
        for ((type, action) in cases) {
            assertEquals(listOf(action), AiEntryPolicy.contextActionsFor(type))
        }
    }

    @Test
    fun `unknown types stay empty even with a transcript`() {
        assertTrue(AiEntryPolicy.contextActionsFor("VIDEO", hasTranscript = true).isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("AUDIO", hasTranscript = true).isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("STICKER", hasTranscript = true).isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("VIDEO", hasTranscript = true))
    }

    @Test
    fun `null and blank types stay empty even with a transcript`() {
        assertTrue(AiEntryPolicy.contextActionsFor(null, hasTranscript = true).isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("", hasTranscript = true).isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("   ", hasTranscript = true).isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions(null, hasTranscript = true))
    }

    @Test
    fun `vertical tab and form feed padding folds to known types`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("\u000Btext\u000C")
        )
        assertEquals(
            listOf(MessageAiAction.TRANSCRIBE),
            AiEntryPolicy.contextActionsFor("\u000Bvoice\u000C")
        )
        assertTrue(
            AiEntryPolicy.contextActionsFor("\u000Bvoice\u000C", hasTranscript = true).isEmpty()
        )
        assertEquals(
            listOf(MessageAiAction.ANALYZE_FILE),
            AiEntryPolicy.contextActionsFor("\u000Bfile\u000C")
        )
    }

    @Test
    fun `zero-width space breaks type matching`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT\u200B").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("\u200BTEXT").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("TEXT\u200B"))
        assertFalse(AiEntryPolicy.hasContextAiActions("\u200BIMAGE"))
    }

    @Test
    fun `full-width letters match nothing`() {
        assertTrue(AiEntryPolicy.contextActionsFor("ＴＥＸＴ").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("ＦＩＬＥ").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("ＶＯＩＣＥ"))
    }

    @Test
    fun `hasContextAiActions mirrors contextActionsFor for gif transcript variants`() {
        assertEquals(
            AiEntryPolicy.contextActionsFor("GIF", hasTranscript = false).isNotEmpty(),
            AiEntryPolicy.hasContextAiActions("GIF", hasTranscript = false)
        )
        assertEquals(
            AiEntryPolicy.contextActionsFor("GIF", hasTranscript = true).isNotEmpty(),
            AiEntryPolicy.hasContextAiActions("GIF", hasTranscript = true)
        )
        assertTrue(AiEntryPolicy.hasContextAiActions("GIF", hasTranscript = true))
        assertEquals(
            listOf(MessageAiAction.ANALYZE_IMAGE),
            AiEntryPolicy.contextActionsFor("GIF", hasTranscript = true)
        )
    }

    @Test
    fun `surface constants use lowercase snake case without spaces`() {
        val surfaces = listOf(
            AiEntryPolicy.PRIMARY_SURFACE,
            AiEntryPolicy.CONTEXT_SURFACE,
            AiEntryPolicy.SETTINGS_SURFACE
        )
        assertTrue(surfaces.all { it == it.lowercase() })
        assertTrue(surfaces.none { ' ' in it })
        assertTrue(surfaces.all { '_' in it })
    }

    @Test
    fun `busy blocks menu and run gate but not composer entry`() {
        assertTrue(AiEntryPolicy.isComposerEntryActive(true))
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = true))
        assertFalse(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = true))
    }

    @Test
    fun `menu opens even while composer entry is inactive`() {
        assertTrue(AiEntryPolicy.canOpenComposerMenu(isBusy = false))
        assertFalse(AiEntryPolicy.isComposerEntryActive(false))
    }

    @Test
    fun `message ai action enum has exactly the four mapped actions`() {
        assertEquals(
            setOf(
                MessageAiAction.TRANSLATE,
                MessageAiAction.TRANSCRIBE,
                MessageAiAction.ANALYZE_IMAGE,
                MessageAiAction.ANALYZE_FILE
            ),
            MessageAiAction.entries.toSet()
        )
    }

    @Test
    fun `known type lists are fresh while unknown types share the empty list`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT") !== AiEntryPolicy.contextActionsFor("TEXT"))
        assertTrue(AiEntryPolicy.contextActionsFor("POLL") === AiEntryPolicy.contextActionsFor("VIDEO"))
    }

    @Test
    fun `composer entry is a weaker gate than full surfaces`() {
        assertTrue(AiEntryPolicy.isComposerEntryActive(true))
        assertFalse(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = false
            )
        )
        assertTrue(
            AiEntryPolicy.shouldShowAiSurfaces(
                chatAiEnabled = true,
                consentAccepted = true,
                userEnabled = true
            )
        )
    }

    @Test
    fun `control char unit separator padding folds to known types`() {
        assertEquals(
            listOf(MessageAiAction.TRANSLATE),
            AiEntryPolicy.contextActionsFor("\u001Ftext\u001F")
        )
        assertTrue(AiEntryPolicy.contextActionsFor("\u001Fvoice\u001F", hasTranscript = true).isEmpty())
    }

    @Test
    fun `accented letters match nothing`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TÉXT").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("téxt").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("FÍLE").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("TÉXT"))
    }

    @Test
    fun `combining mark breaks type matching`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TE\u0301XT").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT\u0301").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("TE\u0301XT"))
    }

    @Test
    fun `setting update blocks only the composer menu`() {
        assertFalse(AiEntryPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
        assertTrue(AiEntryPolicy.canRunContextAction(chatAiEnabled = true, isBusy = false))
        assertTrue(AiEntryPolicy.isComposerEntryActive(true))
    }

    @Test
    fun `underscore joined types match nothing`() {
        assertTrue(AiEntryPolicy.contextActionsFor("TEXT_FILE").isEmpty())
        assertTrue(AiEntryPolicy.contextActionsFor("VOICE_NOTE").isEmpty())
        assertFalse(AiEntryPolicy.hasContextAiActions("TEXT_FILE"))
    }
}
