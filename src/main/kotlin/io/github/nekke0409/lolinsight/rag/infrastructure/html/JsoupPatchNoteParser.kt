package io.github.nekke0409.lolinsight.rag.infrastructure.html

import io.github.nekke0409.lolinsight.rag.application.ParsedPatchNote
import io.github.nekke0409.lolinsight.rag.application.PatchNoteParser
import io.github.nekke0409.lolinsight.rag.application.PatchNoteParsingException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSection
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSnapshot
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

class JsoupPatchNoteParser : PatchNoteParser {
    override val version: String = "jsoup-patch-note-v4"

    override fun parse(snapshot: PatchNoteSnapshot): ParsedPatchNote {
        val document = Jsoup.parse(snapshot.html, snapshot.sourceUrl)
        document.select("script, style, noscript, nav, footer, aside, form, [role=navigation], .navigation, .nav").remove()
        val root =
            document.selectFirst("#patch-notes-container")
                ?: document.selectFirst("article, main, .article-body, .article-content, .content")
                ?: document.body()
                ?: throw PatchNoteParsingException("patch note HTML has no body")
        root.select("img, svg").remove()
        val sections = mutableListOf<PatchNoteSection>()
        val headingStack = mutableListOf<String>()
        val headingLevels = mutableListOf<Int>()
        val bodyParts = mutableListOf<String>()

        fun flushSection() {
            val body = bodyParts.joinToString("\n").trim()
            if (body.isNotBlank()) {
                sections += PatchNoteSection(headingStack.ifEmpty { listOf(snapshot.title) }.toList(), body)
            }
            bodyParts.clear()
        }

        fun process(element: Element) {
            if (element.parents().any { it.tagName() == "nav" || it.hasClass("navigation") || it.hasClass("nav") }) {
                return
            }
            if (element.isHeading()) {
                val heading = element.text().normalizeContent()
                if (heading.isBlank()) {
                    return
                }
                flushSection()
                val level = element.tagName().removePrefix("h").toInt()
                while (headingLevels.lastOrNull()?.let { it >= level } == true) {
                    headingLevels.removeLast()
                    headingStack.removeLast()
                }
                headingLevels += level
                headingStack += heading
            } else if (!element.hasSupportedTextAncestor()) {
                val text = element.text().normalizeContent()
                if (text.isNotBlank()) {
                    bodyParts += if (element.tagName() == "li") "- $text" else text
                }
            }
        }
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(
                    node: Node,
                    depth: Int,
                ) {
                    val element = node as? Element ?: return
                    if (element !== root && element.tagName() in SUPPORTED_TAGS) {
                        process(element)
                    }
                }

                override fun tail(
                    node: Node,
                    depth: Int,
                ) = Unit
            },
            root,
        )
        flushSection()

        if (sections.isEmpty()) {
            throw PatchNoteParsingException("patch note HTML has no supported searchable content")
        }
        return ParsedPatchNote(snapshot, sections)
    }

    private fun Element.isHeading(): Boolean = tagName().matches(Regex("h[1-6]"))

    private fun Element.hasSupportedTextAncestor(): Boolean =
        parents().any { ancestor -> ancestor.tagName() in setOf("p", "li", "table", "blockquote") }

    private fun String.normalizeContent(): String = replace(Regex("\\s+"), " ").trim()

    private companion object {
        val SUPPORTED_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "table", "blockquote")
    }
}
