package io.github.nekke0409.lolinsight.rag.application

import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.OpenAiConfiguration
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagConfiguration
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagPropertiesConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "rag.enabled=true",
        "rag.embedding.dimensions=3",
        "rag.embedding.max-input-characters=8000",
        "rag.embedding.max-total-batch-characters=24000",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    OpenAiConfiguration::class,
    RagPropertiesConfiguration::class,
    RagConfiguration::class,
    PatchNoteRetrievalIntegrationTest.RagTestConfiguration::class,
)
@Testcontainers
class PatchNoteRetrievalIntegrationTest {
    @Autowired
    private lateinit var indexingService: PatchNoteIndexingService

    @Autowired
    private lateinit var retrievalService: PatchNoteRetrievalService

    @Autowired
    private lateinit var embeddingGateway: RecordingEmbeddingGateway

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `indexes a local snapshot and retrieves pgvector cosine results with provenance`() {
        val indexing = indexingService.index(snapshot(FIXTURE_HTML))

        val results =
            retrievalService.search(
                PatchNoteSearchRequest(
                    query = "아리 Q 피해량 변경",
                    patchVersion = "16.99",
                    locale = "ko-KR",
                    topK = 2,
                ),
            )

        assertEquals(PatchNoteIndexingOutcome.INDEXED, indexing.outcome)
        assertEquals(3, indexing.chunkCount)
        assertEquals(2, results.size)
        assertTrue(results.first().evidenceText.contains("Q 피해량"))
        assertEquals("https://example.test/patch/16-99", results.first().sourceUrl)
        assertEquals(listOf("테스트 패치 노트", "챔피언", "아리"), results.first().headingPath)
        assertEquals(0.0, results.first().cosineDistance)
        assertEquals(1.0, results.first().cosineSimilarity)
    }

    @Test
    fun `does not call embedding again for the same active revision`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        val callsAfterFirstIndex = embeddingGateway.calls

        val repeated = indexingService.index(snapshot(FIXTURE_HTML))

        assertEquals(PatchNoteIndexingOutcome.ALREADY_INDEXED, repeated.outcome)
        assertEquals(0, repeated.chunkCount)
        assertEquals(callsAfterFirstIndex, embeddingGateway.calls)
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_patch_note_chunk", Int::class.java))
    }

    @Test
    fun `keeps the active revision searchable when replacement embedding fails`() {
        indexingService.index(snapshot(FIXTURE_HTML))

        assertFailsWith<RagEmbeddingProviderException> {
            indexingService.index(snapshot(FIXTURE_HTML.replace("40에서 45", "provider-failure")))
        }

        val result =
            retrievalService
                .search(
                    PatchNoteSearchRequest("아리", "16.99", "ko-KR", topK = 1),
                ).single()
        assertTrue(result.evidenceText.contains("40에서 45"))
        assertEquals(
            1,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_patch_note_document_revision WHERE is_active", Int::class.java),
        )
    }

    @Test
    fun `filters patch and locale before embedding and rejects an incompatible active corpus`() {
        val callsBeforeSearch = embeddingGateway.calls
        assertEquals(
            emptyList(),
            retrievalService.search(PatchNoteSearchRequest("아리", "missing", "ko-KR", topK = 1)),
        )
        assertEquals(callsBeforeSearch, embeddingGateway.calls)

        indexingService.index(snapshot(FIXTURE_HTML))
        assertEquals(
            emptyList(),
            retrievalService.search(PatchNoteSearchRequest("아리", "16.99", "en-US", topK = 1)),
        )

        jdbcTemplate.update(
            "UPDATE rag_patch_note_document_revision SET embedding_model = 'another-model' WHERE is_active",
        )
        assertFailsWith<RagCorpusContractMismatchException> {
            retrievalService.search(PatchNoteSearchRequest("아리", "16.99", "ko-KR", topK = 1))
        }
    }

    @Test
    fun `runs fixture indexing through pgvector retrieval evidence construction and citation validation`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        var delivered: PatchNoteAnswerGenerationRequest? = null
        val generator =
            object : PatchNoteAnswerGenerator {
                override fun generate(
                    request: PatchNoteAnswerGenerationRequest,
                    timeout: java.time.Duration,
                ): PatchNoteAnswerGenerationResult {
                    delivered = request
                    return PatchNoteAnswerGenerationResult(
                        PatchNoteGeneratedAnswer(
                            PatchNoteAnswerStatus.ANSWERED,
                            listOf(PatchNoteGeneratedStatement("fixture grounded answer", listOf("E2"))),
                            listOf("fixture limitation"),
                        ),
                    )
                }
            }
        val service =
            PatchNoteQuestionService(
                RagProperties(enabled = true, answer = RagAnswerProperties(enabled = true, topK = 3)),
                AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
                retrievalService,
                generator,
            )

        val response = service.answer(PatchNoteQuestionRequest("16.99", "ko-KR", "fixture question"), AnalysisRateLimitKey("test"))

        assertEquals(PatchNoteAnswerStatus.ANSWERED, response.status)
        assertEquals(listOf("E2"), response.citations.map { it.evidenceId })
        assertEquals(delivered?.evidence?.get(1)?.chunkId, response.citations.single().chunkId)
        assertEquals("16.99", response.citations.single().patchVersion)
        assertEquals(3, delivered?.evidence?.size)
    }

    @Test
    fun `does not embed or generate when the requested corpus is empty`() {
        var generated = 0
        val service =
            PatchNoteQuestionService(
                RagProperties(enabled = true, answer = RagAnswerProperties(enabled = true)),
                AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
                retrievalService,
                object : PatchNoteAnswerGenerator {
                    override fun generate(
                        request: PatchNoteAnswerGenerationRequest,
                        timeout: java.time.Duration,
                    ): PatchNoteAnswerGenerationResult {
                        generated++
                        error("generation must not run")
                    }
                },
            )
        val embeddingCalls = embeddingGateway.calls

        val response = service.answer(PatchNoteQuestionRequest("missing", "ko-KR", "fixture question"), AnalysisRateLimitKey("empty"))

        assertEquals(PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(embeddingCalls, embeddingGateway.calls)
        assertEquals(0, generated)
    }

    @Test
    fun `keeps a generator evidence shortage separate from an invalid citation`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        var calls = 0
        val insufficientService =
            questionService { PatchNoteGeneratedAnswer(PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE, emptyList(), listOf("not relevant")) }

        val insufficient =
            insufficientService.answer(
                PatchNoteQuestionRequest("16.99", "ko-KR", "unrelated fixture question"),
                AnalysisRateLimitKey("insufficient"),
            )

        assertEquals(PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE, insufficient.status)
        val invalidCitationService =
            questionService {
                calls++
                PatchNoteGeneratedAnswer(
                    PatchNoteAnswerStatus.ANSWERED,
                    listOf(PatchNoteGeneratedStatement("unsupported", listOf("E999"))),
                    emptyList(),
                )
            }
        assertFailsWith<PatchNoteAnswerInvalidResponseException> {
            invalidCitationService.answer(
                PatchNoteQuestionRequest("16.99", "ko-KR", "fixture question"),
                AnalysisRateLimitKey("invalid-citation"),
            )
        }
        assertEquals(1, calls)
    }

    private fun questionService(answer: (PatchNoteAnswerGenerationRequest) -> PatchNoteGeneratedAnswer): PatchNoteQuestionService =
        PatchNoteQuestionService(
            RagProperties(enabled = true, answer = RagAnswerProperties(enabled = true, topK = 3)),
            AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
            retrievalService,
            object : PatchNoteAnswerGenerator {
                override fun generate(
                    request: PatchNoteAnswerGenerationRequest,
                    timeout: java.time.Duration,
                ): PatchNoteAnswerGenerationResult = PatchNoteAnswerGenerationResult(answer(request))
            },
        )

    private fun snapshot(html: String): PatchNoteSnapshot =
        PatchNoteSnapshot(
            sourceUrl = "https://example.test/patch/16-99",
            title = "가상 fixture",
            patchVersion = "16.99",
            locale = "ko-KR",
            html = html,
            collectedAt = Instant.parse("2026-09-22T00:00:00Z"),
        )

    @TestConfiguration(proxyBeanMethods = false)
    class RagTestConfiguration {
        @Bean
        @Primary
        fun testEmbeddingGateway(): RecordingEmbeddingGateway = RecordingEmbeddingGateway()

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
    }

    class RecordingEmbeddingGateway : EmbeddingGateway {
        override val contract: EmbeddingContract = EmbeddingContract("test-embedding", 3)
        var calls: Int = 0
            private set

        override fun embed(inputs: List<String>): EmbeddingBatch {
            calls += 1
            if (inputs.any { it.contains("provider-failure") }) {
                throw RagEmbeddingProviderException(IllegalStateException("test provider failure"))
            }
            return EmbeddingBatch(contract, inputs.map(::vectorFor))
        }

        private fun vectorFor(input: String): EmbeddingVector =
            when {
                input.contains("아리") -> EmbeddingVector(listOf(1.0f, 0.0f, 0.0f))
                input.contains("가렌") -> EmbeddingVector(listOf(0.0f, 1.0f, 0.0f))
                else -> EmbeddingVector(listOf(0.0f, 0.0f, 1.0f))
            }
    }

    private companion object {
        val FIXTURE_HTML: String =
            requireNotNull(
                PatchNoteRetrievalIntegrationTest::class.java.classLoader.getResource("rag/fictional-patch-note-snapshot.html"),
            ).readText()

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("pgvector/pgvector:0.8.0-pg17")

        @DynamicPropertySource
        @JvmStatic
        fun configurePostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
