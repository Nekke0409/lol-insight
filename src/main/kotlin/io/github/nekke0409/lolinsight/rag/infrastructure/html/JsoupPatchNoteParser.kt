package io.github.nekke0409.lolinsight.rag.infrastructure.html

import io.github.nekke0409.lolinsight.rag.application.ParsedPatchNote
import io.github.nekke0409.lolinsight.rag.application.PatchNoteParser
import io.github.nekke0409.lolinsight.rag.application.PatchNoteParsingException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSection
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSnapshot
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JsoupPatchNoteParser : PatchNoteParser {
    override val version: String = "jsoup-patch-note-v1"

    override fun parse(snapshot: PatchNoteSnapshot): ParsedPatchNote {
        val document = Jsoup.parse(snapshot.html, snapshot.sourceUrl)
        document.select("script, style, noscript, nav, header, footer, aside, form, [role=navigation], .navigation, .nav").remove()
        val root =
            document.selectFirst("article, main, .article-body, .article-content, .content")
                ?: document.body()
                ?: throw PatchNoteParsingException("patch note HTML has no body")
        val sections = mutableListOf<PatchNoteSection>()
        val headingStack = mutableListOf<String>()
        val bodyParts = mutableListOf<String>()

        fun flushSection() {
            val body = bodyParts.joinToString("\n").trim()
            if (body.isNotBlank()) {
                sections += PatchNoteSection(headingStack.ifEmpty { listOf(snapshot.title) }.toList(), body)
            }
            bodyParts.clear()
        }

        root.select("h1, h2, h3, h4, h5, h6, p, li, table").forEach { element ->
            if (element.parents().any { it.tagName() == "nav" || it.hasClass("navigation") || it.hasClass("nav") }) {
                return@forEach
            }
            if (element.isHeading()) {
                flushSection()
                val level = element.tagName().removePrefix("h").toInt()
                while (headingStack.size >= level) {
                    headingStack.removeLast()
                }
                headingStack += element.text().normalizeContent()
            } else {
                val text = element.text().normalizeContent()
                if (text.isNotBlank()) {
                    bodyParts += if (element.tagName() == "li") "- $text" else text
                }
            }
        }
        flushSection()

        if (sections.isEmpty()) {
            throw PatchNoteParsingException("patch note HTML has no supported searchable content")
        }
        return ParsedPatchNote(snapshot, sections)
    }

    private fun Element.isHeading(): Boolean = tagName().matches(Regex("h[1-6]"))

    private fun String.normalizeContent(): String = replace(Regex("\\s+"), " ").trim()
}
