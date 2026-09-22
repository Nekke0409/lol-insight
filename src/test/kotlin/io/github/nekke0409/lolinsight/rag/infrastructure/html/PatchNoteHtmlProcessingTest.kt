package io.github.nekke0409.lolinsight.rag.infrastructure.html

import io.github.nekke0409.lolinsight.rag.application.PatchNoteParsingException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchNoteHtmlProcessingTest {
    private val parser = JsoupPatchNoteParser()

    @Test
    fun `extracts article content while keeping heading paths and excluding navigation`() {
        val document = parser.parse(snapshot(fixture("rag/fictional-patch-note-snapshot.html")))

        assertEquals(3, document.sections.size)
        assertEquals(listOf("테스트 패치 노트", "챔피언", "아리"), document.sections.first().headingPath)
        assertTrue(
            document.sections
                .first()
                .body
                .contains("40에서 45"),
        )
        assertFalse(document.sections.joinToString(" ") { it.body }.contains("홈 게임 정보"))
        assertFalse(document.sections.joinToString(" ") { it.body }.contains("window.tracking"))
        assertEquals(listOf("테스트 패치 노트", "아이템", "연습용 장검"), document.sections.last().headingPath)
    }

    @Test
    fun `chunks each heading section deterministically without crossing champion boundaries`() {
        val document = parser.parse(snapshot(fixture("rag/fictional-patch-note-snapshot.html")))
        val chunker = DeterministicPatchNoteChunker(maximumCharacters = 100)

        val first = chunker.chunk(document)
        val second = chunker.chunk(document)

        assertEquals(first, second)
        assertTrue(first.all { it.body.length <= 100 })
        assertEquals(3, first.size)
        assertTrue(first[0].body.contains("Q 피해량"))
        assertFalse(first[0].body.contains("가렌"))
        assertTrue(first[0].contextPrefix.contains("패치 버전: 16.99"))
    }

    @Test
    fun `rejects html with no supported searchable content`() {
        val html = "<html><body><nav>only navigation</nav><script>ignored()</script></body></html>"

        assertFailsWith<PatchNoteParsingException> {
            parser.parse(snapshot(html))
        }
    }

    private fun snapshot(html: String): PatchNoteSnapshot =
        PatchNoteSnapshot(
            sourceUrl = "https://example.test/patch/16-99",
            title = "가상 fixture",
            patchVersion = "16.99",
            locale = "ko-KR",
            html = html,
            collectedAt = Instant.parse("2026-09-22T00:00:00Z"),
        )

    private fun fixture(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) { "fixture missing: $path" }.readText()
}
