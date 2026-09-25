package io.github.nekke0409.lolinsight.rag.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PatchNoteQuestionServiceTest {
    @Test
    fun `records provider usage and delivered evidence through the application boundary`() {
        val observation = RecordingObservation()
        val capture = RecordingEvidenceCapture()
        val generator =
            object : PatchNoteAnswerGenerator {
                override fun generate(
                    request: PatchNoteAnswerGenerationRequest,
                    timeout: Duration,
                ): PatchNoteAnswerGenerationResult =
                    PatchNoteAnswerGenerationResult(
                        PatchNoteGeneratedAnswer(
                            PatchNoteAnswerStatus.ANSWERED,
                            listOf(PatchNoteGeneratedStatement("grounded", listOf("E1"))),
                            emptyList(),
                        ),
                        PatchNoteAnswerUsage(11, 22, 33),
                        Duration.ofMillis(4),
                    )
            }
        val service = service(storeWithRow(), generator, observation, capture)

        val response = service.answer(PatchNoteQuestionRequest("25.10", "ko-KR", "질문", "fixture-1"), AnalysisRateLimitKey("test"))

        assertEquals(PatchNoteAnswerStatus.ANSWERED, response.status)
        assertEquals(1, observation.summary.queryEmbeddingAttempts)
        assertEquals(7, observation.summary.queryEmbeddingInputTokens)
        assertEquals(PatchNoteAnswerUsage(11, 22, 33), observation.summary.generationUsage)
        assertEquals(1, capture.evidence.size)
        assertEquals(listOf("E1"), capture.response.citations.map { it.evidenceId })
    }

    @Test
    fun `does not record missing usage as zero for an empty corpus`() {
        val observation = RecordingObservation()
        val service = service(store = EmptyStore, generator = FailingGenerator, observation = observation)

        val response = service.answer(PatchNoteQuestionRequest("25.09", "ko-KR", "질문"), AnalysisRateLimitKey("empty"))

        assertEquals(PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(0, observation.summary.queryEmbeddingAttempts)
        assertNull(observation.summary.queryEmbeddingInputTokens)
        assertNull(observation.summary.generationUsage)
    }

    @Test
    fun `does not start generation after a retrieval deadline expires`() {
        var generated = 0
        val slowStore =
            object : PatchNoteDocumentStore by storeWithRow() {
                override fun findActiveEmbeddingContracts(
                    patchVersion: String,
                    locale: String,
                    timeout: Duration,
                ): Set<EmbeddingContract> {
                    Thread.sleep(15)
                    return setOf(EmbeddingContract("test", 3))
                }
            }
        val generator =
            object : PatchNoteAnswerGenerator {
                override fun generate(
                    request: PatchNoteAnswerGenerationRequest,
                    timeout: Duration,
                ): PatchNoteAnswerGenerationResult {
                    generated++
                    error("generation must not start")
                }
            }
        val service =
            service(
                slowStore,
                generator,
                properties =
                    RagProperties(
                        enabled = true,
                        answer =
                            RagAnswerProperties(
                                enabled = true,
                                retrievalTimeout = Duration.ofMillis(1),
                                executionDeadline = Duration.ofMillis(5),
                            ),
                    ),
            )

        assertFailsWith<PatchNoteAnswerDeadlineExceededException> {
            service.answer(PatchNoteQuestionRequest("25.10", "ko-KR", "질문"), AnalysisRateLimitKey("deadline"))
        }
        assertEquals(0, generated)
    }

    private fun service(
        store: PatchNoteDocumentStore,
        generator: PatchNoteAnswerGenerator,
        observation: RecordingObservation = RecordingObservation(),
        capture: PatchNoteAnswerEvidenceObserver = NoOpPatchNoteAnswerEvidenceObserver,
        properties: RagProperties = RagProperties(enabled = true, answer = RagAnswerProperties(enabled = true)),
    ): PatchNoteQuestionService =
        PatchNoteQuestionService(
            properties,
            AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
            PatchNoteRetrievalService(TestEmbeddingGateway, store, maximumTopK = 10, maximumEvidenceCharacters = 600),
            generator,
            observation,
            capture,
        )

    private fun storeWithRow(): PatchNoteDocumentStore =
        object : PatchNoteDocumentStore by EmptyStore {
            override fun findActiveEmbeddingContracts(
                patchVersion: String,
                locale: String,
            ): Set<EmbeddingContract> = setOf(TestEmbeddingGateway.contract)

            override fun findActiveEmbeddingContracts(
                patchVersion: String,
                locale: String,
                timeout: Duration,
            ): Set<EmbeddingContract> = findActiveEmbeddingContracts(patchVersion, locale)

            override fun search(
                patchVersion: String,
                locale: String,
                contract: EmbeddingContract,
                queryEmbedding: EmbeddingVector,
                topK: Int,
            ): List<PatchNoteSearchRow> =
                listOf(
                    PatchNoteSearchRow(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        "fixture",
                        "https://example.test/patch",
                        patchVersion,
                        locale,
                        "revision",
                        listOf("챔피언", "fixture"),
                        "grounded fixture text",
                        0.0,
                    ),
                )

            override fun search(
                patchVersion: String,
                locale: String,
                contract: EmbeddingContract,
                queryEmbedding: EmbeddingVector,
                topK: Int,
                timeout: Duration,
            ): List<PatchNoteSearchRow> = search(patchVersion, locale, contract, queryEmbedding, topK)
        }

    private object EmptyStore : PatchNoteDocumentStore {
        override fun findRevision(revisionFingerprint: String): StoredPatchNoteRevision? = null

        override fun activateOrStore(indexed: IndexedPatchNote): StoredPatchNoteRevision = error("not used")

        override fun findActiveEmbeddingContracts(
            patchVersion: String,
            locale: String,
        ): Set<EmbeddingContract> = emptySet()

        override fun search(
            patchVersion: String,
            locale: String,
            contract: EmbeddingContract,
            queryEmbedding: EmbeddingVector,
            topK: Int,
        ): List<PatchNoteSearchRow> = emptyList()
    }

    private object TestEmbeddingGateway : EmbeddingGateway {
        override val contract: EmbeddingContract = EmbeddingContract("test", 3)

        override fun embed(inputs: List<String>): EmbeddingBatch = EmbeddingBatch(contract, listOf(EmbeddingVector(listOf(1f, 0f, 0f))), 7)
    }

    private object FailingGenerator : PatchNoteAnswerGenerator {
        override fun generate(
            request: PatchNoteAnswerGenerationRequest,
            timeout: Duration,
        ): PatchNoteAnswerGenerationResult = error("generation must not run")
    }

    private class RecordingObservation : PatchNoteQuestionObservationRecorder {
        lateinit var summary: PatchNoteQuestionExecutionSummary

        override fun record(summary: PatchNoteQuestionExecutionSummary) {
            this.summary = summary
        }
    }

    private class RecordingEvidenceCapture : PatchNoteAnswerEvidenceObserver {
        lateinit var evidence: List<PatchNoteEvidence>
        lateinit var response: PatchNoteQuestionResponse

        override fun record(
            request: PatchNoteQuestionRequest,
            evidence: List<PatchNoteEvidence>,
            answer: PatchNoteGeneratedAnswer,
            response: PatchNoteQuestionResponse,
        ) {
            this.evidence = evidence
            this.response = response
        }
    }
}
