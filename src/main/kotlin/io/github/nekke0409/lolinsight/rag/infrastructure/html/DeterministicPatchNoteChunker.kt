package io.github.nekke0409.lolinsight.rag.infrastructure.html

import io.github.nekke0409.lolinsight.rag.application.ParsedPatchNote
import io.github.nekke0409.lolinsight.rag.application.PatchNoteChunk
import io.github.nekke0409.lolinsight.rag.application.PatchNoteChunker
import io.github.nekke0409.lolinsight.rag.application.PatchNoteChunkingException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSection

class DeterministicPatchNoteChunker(
    private val maximumCharacters: Int,
) : PatchNoteChunker {
    init {
        require(maximumCharacters >= 100) { "maximumCharacters must be at least 100" }
    }

    override val version: String = "section-preserving-chunker-v1"

    override fun chunk(document: ParsedPatchNote): List<PatchNoteChunk> {
        val chunks =
            document.sections
                .flatMap { section ->
                    splitSection(section).map { body -> section.headingPath to body }
                }.mapIndexed { index, (headingPath, body) ->
                    PatchNoteChunk(
                        index = index,
                        headingPath = headingPath,
                        contextPrefix = contextPrefix(document, headingPath),
                        body = body,
                    )
                }
        if (chunks.isEmpty()) {
            throw PatchNoteChunkingException("patch note has no chunkable sections")
        }
        return chunks
    }

    private fun splitSection(section: PatchNoteSection): List<String> {
        val paragraphs = section.body.split("\n").flatMap(::splitOversizedParagraph)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (paragraph in paragraphs) {
            if (current.isNotEmpty() && current.length + 1 + paragraph.length > maximumCharacters) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) {
                current.append('\n')
            }
            current.append(paragraph)
        }
        if (current.isNotEmpty()) {
            chunks += current.toString()
        }
        return chunks
    }

    private fun splitOversizedParagraph(paragraph: String): List<String> {
        if (paragraph.length <= maximumCharacters) {
            return listOf(paragraph)
        }
        val pieces = mutableListOf<String>()
        var remaining = paragraph.trim()
        while (remaining.length > maximumCharacters) {
            val splitAt = remaining.lastIndexOf(' ', startIndex = maximumCharacters).takeIf { it > 0 } ?: maximumCharacters
            pieces += remaining.take(splitAt).trimEnd()
            remaining = remaining.drop(splitAt).trimStart()
        }
        if (remaining.isNotBlank()) {
            pieces += remaining
        }
        return pieces
    }

    private fun contextPrefix(
        document: ParsedPatchNote,
        headingPath: List<String>,
    ): String =
        listOf(
            "패치 버전: ${document.snapshot.patchVersion}",
            "문서 제목: ${document.snapshot.title}",
            "제목 경로: ${headingPath.joinToString(" > ")}",
        ).joinToString("\n")
}
