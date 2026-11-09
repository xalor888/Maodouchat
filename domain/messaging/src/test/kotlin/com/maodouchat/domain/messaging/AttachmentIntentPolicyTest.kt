package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentIntentPolicyTest {

    @Test
    fun `sticker code strips scheme`() {
        assertEquals("abc123", AttachmentIntentPolicy.parseStickerCode("sticker://abc123"))
    }

    @Test
    fun `sticker code without scheme passes through`() {
        assertEquals("abc123", AttachmentIntentPolicy.parseStickerCode("abc123"))
    }

    @Test
    fun `blank sticker code returns null`() {
        assertNull(AttachmentIntentPolicy.parseStickerCode("sticker://   "))
        assertNull(AttachmentIntentPolicy.parseStickerCode(""))
    }

    @Test
    fun `sticker code truncates to 32 chars`() {
        assertEquals("a".repeat(32), AttachmentIntentPolicy.parseStickerCode("sticker://" + "a".repeat(40)))
    }

    @Test
    fun `geo location parses lat and lng`() {
        assertEquals(
            GeoLocation(31.2304, 121.4737),
            AttachmentIntentPolicy.parseGeoLocation("geo:31.2304,121.4737"),
        )
    }

    @Test
    fun `geo location strips query from lng`() {
        assertEquals(
            GeoLocation(31.2, 121.5),
            AttachmentIntentPolicy.parseGeoLocation("geo:31.2,121.5?z=15"),
        )
    }

    @Test
    fun `geo location defaults malformed numbers to zero`() {
        assertEquals(
            GeoLocation(0.0, 0.0),
            AttachmentIntentPolicy.parseGeoLocation("geo:abc,def"),
        )
    }

    @Test
    fun `geo location without lng defaults lng to zero`() {
        assertEquals(
            GeoLocation(31.2, 0.0),
            AttachmentIntentPolicy.parseGeoLocation("geo:31.2"),
        )
    }

    @Test
    fun `contact card parses user id and name param`() {
        assertEquals(
            ContactCard("user-1", "Alice"),
            AttachmentIntentPolicy.parseContactCard("contact://user-1?name=Alice&x=1", null),
        )
    }

    @Test
    fun `contact card falls back to caption for display name`() {
        assertEquals(
            ContactCard("user-2", "Bob"),
            AttachmentIntentPolicy.parseContactCard("contact://user-2", "Bob"),
        )
    }

    @Test
    fun `contact card falls back to contact without caption`() {
        assertEquals(
            ContactCard("user-2", "contact"),
            AttachmentIntentPolicy.parseContactCard("contact://user-2", null),
        )
    }

    @Test
    fun `contact card without scheme separator parses bare id`() {
        assertEquals(
            ContactCard("user-3", "Cid"),
            AttachmentIntentPolicy.parseContactCard("contact:user-3?name=Cid", null),
        )
    }

    @Test
    fun `kind maps to message type`() {
        assertEquals(MessageType.IMAGE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.IMAGE))
        assertEquals(MessageType.VIDEO, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.VIDEO))
        assertEquals(MessageType.VOICE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.VOICE))
        assertEquals(MessageType.FILE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.FILE))
        assertEquals(MessageType.GIF, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.GIF))
        assertEquals(MessageType.FILE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.STICKER))
        assertEquals(MessageType.FILE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.LOCATION))
        assertEquals(MessageType.FILE, AttachmentIntentPolicy.messageTypeForKind(AttachmentKind.CONTACT))
    }

    @Test
    fun `visibility keeps view once only for supported types`() {
        assertEquals(
            AttachmentVisibility(true, false),
            AttachmentIntentPolicy.resolveAttachmentVisibility(MessageType.IMAGE, true, false),
        )
        assertEquals(
            AttachmentVisibility(false, false),
            AttachmentIntentPolicy.resolveAttachmentVisibility(MessageType.TEXT, true, false),
        )
    }

    @Test
    fun `visibility drops spoiler when view once is on`() {
        assertEquals(
            AttachmentVisibility(true, false),
            AttachmentIntentPolicy.resolveAttachmentVisibility(MessageType.IMAGE, true, true),
        )
        assertEquals(
            AttachmentVisibility(false, true),
            AttachmentIntentPolicy.resolveAttachmentVisibility(MessageType.VIDEO, false, true),
        )
    }

    @Test
    fun `visibility drops both for unsupported types`() {
        assertEquals(
            AttachmentVisibility(false, false),
            AttachmentIntentPolicy.resolveAttachmentVisibility(MessageType.FILE, true, true),
        )
    }
}
