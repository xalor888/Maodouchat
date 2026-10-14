package com.maodouchat.ui.component

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Hr : MdBlock
}
