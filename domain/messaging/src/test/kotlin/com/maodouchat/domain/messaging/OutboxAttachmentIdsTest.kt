package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxAttachmentIdsTest {

    @Test
    fun `payload with attachments returns their ids in order`() {
        val payload = """{"version":1,"type":"TEXT","body":"hi","attachmentIds":["att-1","att-2"]}"""

        assertEquals(listOf("att-1", "att-2"), outboxAttachmentIds(payload))
    }

    @Test
    fun `payload without attachment ids returns empty list`() {
        val payload = """{"version":1,"type":"TEXT","body":"hi"}"""

        assertEquals(emptyList(), outboxAttachmentIds(payload))
    }

    @Test
    fun `unparseable payload returns empty list instead of throwing`() {
        assertEquals(emptyList(), outboxAttachmentIds("not-json"))
        assertEquals(emptyList(), outboxAttachmentIds(""))
        assertEquals(emptyList(), outboxAttachmentIds("""{"version":1}"""))
    }

    @Test
    fun `non object payload returns empty list`() {
        assertEquals(emptyList(), outboxAttachmentIds("[1,2,3]"))
    }
}
