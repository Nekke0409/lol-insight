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

    @Test
    fun `keeps blockquote reasons and nested list text under their correct sibling heading paths`() {
        val html =
            """
            <main>
              <section id="patch-notes-container">
                <header><h2>챔피언</h2></header>
                <h3>초가스</h3>
                <blockquote><p>중단 공격로 성능을 낮추고자 합니다.</p></blockquote>
                <h4><img alt="Image" /> Q - 파열</h4>
                <ul><li>피해량: 80 ⇒ 70</li></ul>
                <h3>룰루</h3>
                <p>개인 랭크에서 강력합니다.</p>
                <h2>아이템</h2>
                <p>다음 h2는 챔피언의 자식이 아닙니다.</p>
              </section>
              <section><h2>관련 글</h2><p>검색 대상이 아닙니다.</p></section>
            </main>
            """.trimIndent()

        val sections = parser.parse(snapshot(html)).sections

        assertEquals(listOf("챔피언", "초가스"), sections[0].headingPath)
        assertTrue(sections[0].body.contains("중단 공격로 성능"))
        assertEquals(listOf("챔피언", "초가스", "Q - 파열"), sections[1].headingPath)
        assertTrue(sections[1].body.contains("피해량: 80 ⇒ 70"))
        assertEquals(listOf("챔피언", "룰루"), sections[2].headingPath)
        assertEquals(listOf("아이템"), sections[3].headingPath)
        assertFalse(sections.joinToString(" ") { it.body }.contains("관련 글"))
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
