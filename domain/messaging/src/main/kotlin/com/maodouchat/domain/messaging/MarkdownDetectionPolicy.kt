package com.maodouchat.domain.messaging

// Markdown 判定：一段文本是否含 Markdown 语法。
// 由 :core:model 的 ChatMarkdown 迁入；toPlainText 已随 ChatMarkdown 迁入 :domain:messaging。
object MarkdownDetectionPolicy {
    // 原先这三个 matcher 声明在 ui/component/MarkdownParser.kt（internal），
    // 只有 looksLikeMarkdown 在用；跟着搬过来，避免 messaging -> ui 的反向引用。
    private val MD_LINK_REGEX = Regex("""\[[^\]]+\]\([^\)]+\)""")
    private val MD_IMAGE_REGEX = Regex("""!\[[^\]]*\]\([^\)]+\)""")
    private val MD_ORDERED_LIST_ML_REGEX = Regex("""^\d+\.\s""", RegexOption.MULTILINE)

    fun looksLikeMarkdown(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lines()
        if (lines.any { it.startsWith("#") || it.startsWith("> ") || it.startsWith("- ") || it.startsWith("* ") || it.startsWith("```") }) return true
        if (text.contains("**") || text.contains("__") || text.contains("`") || text.contains("~~") || text.contains("||")) return true
        if (MD_LINK_REGEX.containsMatchIn(text)) return true
        if (MD_ORDERED_LIST_ML_REGEX.containsMatchIn(text)) return true
        // GFM-ish table rows: a|b with a separator line of dashes
        if (lines.any { line -> line.contains("|") && line.count { it == '|' } >= 2 }) return true
        // Task list / footnotes / images
        if (lines.any {
                val t = it.trimStart()
                t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("- [X]")
            }) return true
        if (text.contains("]:") && text.contains("[^")) return true
        if (MD_IMAGE_REGEX.containsMatchIn(text)) return true
        if (text.contains("==") && text.indexOf("==") != text.lastIndexOf("==")) return true
        if (text.contains("++") && text.indexOf("++") != text.lastIndexOf("++")) return true
        if (text.contains("~~sub:") || text.contains("^sup:")) return true
        if (text.contains("[[") && text.contains("]]")) return true
        if (text.contains("[^") && text.contains("]")) return true
        if (text.contains("www.") || text.contains("mailto:")) return true
        if (text.contains("%%") && text.indexOf("%%") != text.lastIndexOf("%%")) return true
        if (text.contains("~cite:") || text.contains("~~cite:")) return true
        if (text.contains("~abbr:")) return true
        if (text.contains("~note:")) return true
        if (text.contains("~tip:")) return true
        if (text.contains("~warn:")) return true
        if (text.contains("~lock:")) return true
        if (text.contains("~pin:")) return true
        if (text.contains("~draft:")) return true
        if (text.contains("~at:")) return true
        if (text.contains("~inv:")) return true
        if (text.contains("~nudge:")) return true
        if (text.contains("~code:")) return true
        if (text.contains("~qr:")) return true
        if (text.contains("~card:")) return true
        if (text.contains("~blur:")) return true
        if (text.contains("~dl:")) return true
        if (text.contains("~loc:")) return true
        if (text.contains("~file:")) return true
        if (text.contains("~sec:")) return true
        if (text.contains("~wm:")) return true
        if (text.contains("~vc:")) return true
        if (text.contains("~vd:")) return true
        if (text.contains("~wp:")) return true
        if (text.contains("~fs:")) return true
        if (text.contains("~ur:")) return true
        if (text.contains("~rg:")) return true
        if (text.contains("~sd:")) return true
        if (text.contains("~pv:")) return true
        if (text.contains("~ph:")) return true
        if (text.contains("~tk:")) return true
        if (text.contains("~qd:")) return true
        if (text.contains("~oa:")) return true
        if (text.contains("~cl:")) return true
        if (text.contains("~hz:")) return true
        if (text.contains("~an:")) return true
        if (text.contains("~nv:")) return true
        if (text.contains("~sh:")) return true
        if (text.contains("~cp:")) return true
        if (text.contains("~ex:")) return true
        if (text.contains("~lw:")) return true
        if (text.contains("~fw:")) return true
        if (text.contains("~ce:")) return true
        if (text.contains("~vf:")) return true
        if (text.contains("~ss:")) return true
        if (text.contains("~pq:")) return true
        if (text.contains("~cr:")) return true
        if (text.contains("~mk:")) return true
        if (text.contains("~ft:")) return true
        if (text.contains("~sr:")) return true
        if (text.contains("~ll:")) return true
        if (text.contains("~pm:")) return true
        if (text.contains("~uf:")) return true
        if (text.contains("~rx:")) return true
        if (text.contains("~st:")) return true
        if (text.contains("~mf:")) return true
        if (text.contains("~tp:")) return true
        if (text.contains("~rr:")) return true
        if (text.contains("~ps:")) return true
        if (text.contains("~ls:")) return true
        if (text.contains("~rc:")) return true
        if (text.contains("~img:")) return true
        if (text.contains("~vid:")) return true
        if (text.contains("~sum:")) return true
        if (text.contains("~rw:")) return true
        if (text.contains("~sg:")) return true
        if (text.contains("~tx:")) return true
        if (text.contains("~az:")) return true
        if (text.contains("~ga:")) return true
        if (text.contains("~df:")) return true
        if (text.contains("~sm:")) return true
        if (text.contains("~gf:")) return true
        if (text.contains("~wm:")) return true
        if (text.contains("~tr:")) return true
        if (lines.any { val t = it.trim(); t == "---" || t == "***" || t == "___" }) return true
        // Definition-style lines: Term: definition
        if (lines.any { line ->
            val t = line.trim()
            ':' in t && !t.startsWith("http") && t.indexOf(':') in 1..40 && t.substringAfter(':').isNotBlank()
        } && lines.size >= 2) return true
        return false
    }

}
