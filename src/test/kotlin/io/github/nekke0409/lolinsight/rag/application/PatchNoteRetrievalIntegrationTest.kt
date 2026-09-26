package io.github.nekke0409.lolinsight.rag.application

import io.github.nekke0409.lolinsight.agent.application.AgentModelContinuation
import io.github.nekke0409.lolinsight.agent.application.AgentModelGateway
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolOutput
import io.github.nekke0409.lolinsight.agent.application.AgentModelTurn
import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteProperties
import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteRetrievalException
import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteScope
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionService
import io.github.nekke0409.lolinsight.agent.application.AgentStatementBasis
import io.github.nekke0409.lolinsight.agent.application.AgentStructuredFinalAnswer
import io.github.nekke0409.lolinsight.agent.application.AgentStructuredStatement
import io.github.nekke0409.lolinsight.agent.application.AgentTerminationReason
import io.github.nekke0409.lolinsight.agent.application.AgentToolDispatcher
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutionRunner
import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.OpenAiConfiguration
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitProperties
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContext
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextPlayer
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextSample
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerPositionStatistics
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagConfiguration
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagPropertiesConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
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
import tools.jackson.databind.json.JsonMapper
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

    @Test
    fun `connects the scripted Agent loop to actual pgvector retrieval and validates only delivered citations`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        val comparisonContextService =
            mock(io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService::class.java)
        val properties =
            AgentQuestionProperties(
                enabled = true,
                patchNotes = AgentPatchNoteProperties(enabled = true, topK = 3, maxEvidenceCharacters = 5_000),
            )
        val dispatcher =
            AgentToolDispatcher(
                JsonMapper.builder().build(),
                comparisonContextService,
                mock(io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService::class.java),
                properties,
                retrievalService,
            )
        val model =
            ScriptedAgentModel(
                toolTurn("call-patch", "search_patch_notes", "{\"query\":\"아리 Q 피해량 변경\"}"),
                finalTurn(
                    AgentStructuredFinalAnswer(
                        statements =
                            listOf(
                                AgentStructuredStatement(
                                    "아리 Q 피해량 변경 근거가 있습니다.",
                                    AgentStatementBasis.PATCH_NOTE,
                                    listOf("PATCH_E1"),
                                    "search_patch_notes",
                                ),
                            ),
                        limitations = emptyList(),
                    ),
                ),
            )
        val service =
            AgentQuestionService(
                properties,
                mock(AnalysisGenerationRateLimiter::class.java),
                model,
                dispatcher,
                DirectToolExecutionRunner,
                ragProperties = RagProperties(enabled = true),
            )

        val response =
            service.answer(
                "ignored-player",
                "KR1",
                "패치 노트 질문",
                AnalysisRateLimitKey("agent-patch"),
                AgentPatchNoteScope("16.99", "ko-KR"),
            )

        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertEquals(listOf("search_patch_notes"), response.usedTools.map { it.name })
        assertEquals(listOf("PATCH_E1"), response.citations.map { it.evidenceId })
        assertTrue(
            model.outputs
                .single()
                .single()
                .output
                .contains("PATCH_E1"),
        )
        assertTrue(
            model.outputs
                .single()
                .single()
                .output
                .contains("아리"),
        )
        assertTrue(
            model.outputs
                .single()
                .single()
                .output
                .contains("resultCount"),
        )
        verifyNoInteractions(comparisonContextService)
    }

    @Test
    fun `runs statistics then patch-note retrieval within the Agent limits without mixing their provenance`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        val comparisonContextService = mock(PlayerComparisonContextService::class.java)
        `when`(comparisonContextService.buildContext("Example", "KR1", 0, 20)).thenReturn(statisticsContext())
        val properties =
            AgentQuestionProperties(
                enabled = true,
                patchNotes = AgentPatchNoteProperties(enabled = true, topK = 3, maxEvidenceCharacters = 5_000),
            )
        val dispatcher =
            AgentToolDispatcher(
                JsonMapper.builder().build(),
                comparisonContextService,
                mock(PlayerComparisonFeatureService::class.java),
                properties,
                retrievalService,
            )
        val model =
            ScriptedAgentModel(
                toolTurn("call-stats", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}"),
                toolTurn("call-patch", "search_patch_notes", "{\"query\":\"아리 Q 피해량 변경\"}"),
                finalTurn(
                    AgentStructuredFinalAnswer(
                        statements =
                            listOf(
                                AgentStructuredStatement(
                                    "최근 MID 통계입니다.",
                                    AgentStatementBasis.TOOL,
                                    emptyList(),
                                    "get_ranked_stats",
                                ),
                                AgentStructuredStatement(
                                    "아리 Q 피해량 변경 근거가 있습니다.",
                                    AgentStatementBasis.PATCH_NOTE,
                                    listOf("PATCH_E1"),
                                    "search_patch_notes",
                                ),
                            ),
                        limitations = emptyList(),
                    ),
                ),
            )
        val service =
            AgentQuestionService(
                properties,
                mock(AnalysisGenerationRateLimiter::class.java),
                model,
                dispatcher,
                DirectToolExecutionRunner,
                ragProperties = RagProperties(enabled = true),
            )
        val embeddingCalls = embeddingGateway.calls

        val response =
            service.answer(
                "Example",
                "KR1",
                "최근 MID 통계와 패치 변경을 알려줘",
                AnalysisRateLimitKey("agent-mixed"),
                AgentPatchNoteScope("16.99", "ko-KR"),
            )

        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertEquals(listOf("get_ranked_stats", "search_patch_notes"), response.usedTools.map { it.name })
        assertEquals(listOf("PATCH_E1"), response.citations.map { it.evidenceId })
        assertEquals(1, model.startCalls)
        assertEquals(2, model.continueCalls)
        assertEquals(2, model.outputs.size)
        assertTrue(
            model.outputs[0]
                .single()
                .output
                .contains("\"scope\":\"POSITION\""),
        )
        assertTrue(
            model.outputs[1]
                .single()
                .output
                .contains("PATCH_E1"),
        )
        assertEquals(embeddingCalls + 1, embeddingGateway.calls)
        verify(comparisonContextService).buildContext("Example", "KR1", 0, 20)
    }

    @Test
    fun `returns structured no evidence without query embedding for an empty Agent scope`() {
        val properties = AgentQuestionProperties(enabled = true, patchNotes = AgentPatchNoteProperties(enabled = true))
        val dispatcher =
            AgentToolDispatcher(
                JsonMapper.builder().build(),
                mock(io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService::class.java),
                mock(io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService::class.java),
                properties,
                retrievalService,
            )
        val model =
            ScriptedAgentModel(
                toolTurn("call-empty", "search_patch_notes", "{\"query\":\"아리\"}"),
                finalTurn(
                    AgentStructuredFinalAnswer(
                        listOf(AgentStructuredStatement("저장된 근거가 부족합니다.", AgentStatementBasis.LIMITATION, emptyList(), null)),
                        listOf("요청 범위에 검색 가능한 corpus가 없습니다."),
                    ),
                ),
            )
        val service =
            AgentQuestionService(
                properties,
                mock(AnalysisGenerationRateLimiter::class.java),
                model,
                dispatcher,
                DirectToolExecutionRunner,
                ragProperties = RagProperties(enabled = true),
            )
        val embeddingCalls = embeddingGateway.calls

        val response =
            service.answer(
                "ignored-player",
                "KR1",
                "빈 corpus 질문",
                AnalysisRateLimitKey("agent-empty"),
                AgentPatchNoteScope("16.98", "ko-KR"),
            )

        assertEquals(embeddingCalls, embeddingGateway.calls)
        assertTrue(response.citations.isEmpty())
        assertTrue(
            model.outputs
                .single()
                .single()
                .output
                .contains("NO_EVIDENCE"),
        )
    }

    @Test
    fun `does not turn a patch-note embedding failure into no evidence`() {
        indexingService.index(snapshot(FIXTURE_HTML))
        val properties = AgentQuestionProperties(enabled = true, patchNotes = AgentPatchNoteProperties(enabled = true))
        val dispatcher =
            AgentToolDispatcher(
                JsonMapper.builder().build(),
                mock(PlayerComparisonContextService::class.java),
                mock(PlayerComparisonFeatureService::class.java),
                properties,
                retrievalService,
            )
        val service =
            AgentQuestionService(
                properties,
                mock(AnalysisGenerationRateLimiter::class.java),
                ScriptedAgentModel(toolTurn("call-error", "search_patch_notes", "{\"query\":\"provider-failure\"}")),
                dispatcher,
                DirectToolExecutionRunner,
                ragProperties = RagProperties(enabled = true),
            )

        assertFailsWith<AgentPatchNoteRetrievalException> {
            service.answer(
                "Example",
                "KR1",
                "패치 노트 질문",
                AnalysisRateLimitKey("agent-retrieval-error"),
                AgentPatchNoteScope("16.99", "ko-KR"),
            )
        }
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

    private fun statisticsContext(): PlayerComparisonContext =
        PlayerComparisonContext(
            player = PlayerComparisonContextPlayer("Example", "KR1"),
            targetPuuid = "target-puuid",
            rankContext = null,
            sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 7),
            positionStatistics =
                listOf(
                    PlayerPositionStatistics(
                        position = "MID",
                        games = 7,
                        wins = 4,
                        winRate = 4.0 / 7.0,
                        averageKda = 3.2,
                        averageCsPerMinute = 7.1,
                        averageGoldPerMinute = 410.0,
                        averageDamagePerMinute = 610.0,
                        averageVisionPerMinute = 1.2,
                        averageKillParticipation = 0.48,
                        averageDamageShare = 0.24,
                    ),
                ),
            championPositionStatistics = emptyList(),
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

    private class ScriptedAgentModel(
        vararg turns: AgentModelTurn,
    ) : AgentModelGateway {
        private val turns = ArrayDeque(turns.toList())
        val outputs = mutableListOf<List<AgentModelToolOutput>>()
        var startCalls = 0
            private set
        var continueCalls = 0
            private set

        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: java.time.Duration,
        ): AgentModelTurn {
            startCalls++
            return turns.removeFirst()
        }

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: java.time.Duration,
        ): AgentModelTurn {
            continueCalls++
            this.outputs += outputs
            return turns.removeFirst()
        }
    }

    private fun toolTurn(
        callId: String,
        name: String,
        arguments: String,
    ): AgentModelTurn = AgentModelTurn(null, listOf(AgentModelToolCall(callId, name, arguments)), TestContinuation)

    private fun finalTurn(finalAnswer: AgentStructuredFinalAnswer): AgentModelTurn =
        AgentModelTurn(null, emptyList(), TestContinuation, finalAnswer = finalAnswer)

    private object TestContinuation : AgentModelContinuation

    private object DirectToolExecutionRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: java.time.Duration,
            action: () -> T,
        ): T = action()
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
