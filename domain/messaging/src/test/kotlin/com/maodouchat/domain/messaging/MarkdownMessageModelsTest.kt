package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class MarkdownMessageModelsTest {

    @Test
    fun `markdown blocks cover all seven shapes`() {
        val blocks: List<MdBlock> = listOf(
            MdBlock.Paragraph("p"),
            MdBlock.Heading(2, "h"),
            MdBlock.Quote("q"),
            MdBlock.ListItem("*", "i"),
            MdBlock.Code("c"),
            MdBlock.Table(listOf(listOf("a"))),
            MdBlock.Hr,
        )
        assertEquals(7, blocks.size)
        assertTrue(blocks[1] is MdBlock.Heading)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals("a", (blocks[5] as MdBlock.Table).rows.single().single())
    }
}
