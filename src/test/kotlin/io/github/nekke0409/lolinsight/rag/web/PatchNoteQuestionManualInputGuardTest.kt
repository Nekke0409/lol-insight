package io.github.nekke0409.lolinsight.rag.web

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitProperties
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import io.github.nekke0409.lolinsight.rag.application.EmbeddingBatch
import io.github.nekke0409.lolinsight.rag.application.EmbeddingContract
import io.github.nekke0409.lolinsight.rag.application.EmbeddingGateway
import io.github.nekke0409.lolinsight.rag.application.EmbeddingVector
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerGenerationRequest
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerGenerationResult
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerGenerator
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerStatus
import io.github.nekke0409.lolinsight.rag.application.PatchNoteDocumentStore
import io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedAnswer
import io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedStatement
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteRetrievalService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteSearchRow
import io.github.nekke0409.lolinsight.rag.infrastructure.ManualPatchNoteAnswerEvidenceObserver
import io.github.nekke0409.lolinsight.rag.infrastructure.ManualRagSmokePlanConfigurationException
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchNoteQuestionManualInputGuardTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `passes an equivalent Unicode escape request unchanged through controller to both provider doubles`() {
        val fixture = fixture()
        val json = unicodeEscapedJson(QUESTION)

        fixture.mockMvc
            .perform(request(json, "utf8-grounded"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value(ANSWER))
            .andExpect(jsonPath("$.citations[0].headingPath[0]").value("챔피언 > 한글"))

        assertEquals(listOf(QUESTION), fixture.embedding.inputs)
        assertEquals(listOf(QUESTION), fixture.generator.questions)
        val capture = Files.readString(fixture.capturePath("utf8-grounded"), StandardCharsets.UTF_8)
        assertTrue(capture.contains("\"inputMatches\":true"))
        assertTrue(capture.contains("\"providerStageEntered\":true"))
        assertTrue(capture.contains("챔피언 > 한글"))
    }

    @Test
    fun `rejects a damaged or different manual question before retrieval and both providers`() {
        listOf("?".repeat(QUESTION.length), "서로 다른 질문").forEach { question ->
            val fixture = fixture()

            fixture.mockMvc
                .perform(request(json(question, "16.99", "ko-KR"), "utf8-grounded"))
                .andExpect(status().isUnprocessableContent)
                .andExpect(jsonPath("$.code").value("MANUAL_INPUT_MISMATCH"))
                .andExpect(jsonPath("$.reason").value("QUESTION_MISMATCH"))

            assertTrue(fixture.embedding.inputs.isEmpty())
            assertTrue(fixture.generator.questions.isEmpty())
            val capture = Files.readString(fixture.capturePath("utf8-grounded"), StandardCharsets.UTF_8)
            assertTrue(capture.contains("\"inputMatches\":false"))
            assertTrue(capture.contains("\"providerStageEntered\":false"))
        }
    }

    @Test
    fun `rejects changed scope and unapproved IDs before providers`() {
        listOf(
            Triple(json(QUESTION, "16.98", "ko-KR"), "utf8-grounded", "PATCH_VERSION_MISMATCH"),
            Triple(json(QUESTION, "16.99", "en-US"), "utf8-grounded", "LOCALE_MISMATCH"),
            Triple(json(QUESTION, "16.99", "ko-KR"), "not-approved", "UNAPPROVED_QUESTION_ID"),
        ).forEach { (body, id, reason) ->
            val fixture = fixture()

            fixture.mockMvc
                .perform(request(body, id))
                .andExpect(status().isUnprocessableContent)
                .andExpect(jsonPath("$.code").value("MANUAL_INPUT_MISMATCH"))
                .andExpect(jsonPath("$.reason").value(reason))

            assertTrue(fixture.embedding.inputs.isEmpty())
            assertTrue(fixture.generator.questions.isEmpty())
        }
    }

    @Test
    fun `rejects duplicate question IDs without another provider attempt`() {
        val fixture = fixture()

        fixture.mockMvc.perform(request(json(QUESTION, "16.99", "ko-KR"), "utf8-grounded")).andExpect(status().isOk)
        fixture.mockMvc.perform(request(json(QUESTION, "16.99", "ko-KR"), "utf8-grounded")).andExpect(status().isTooManyRequests)

        assertEquals(1, fixture.embedding.inputs.size)
        assertEquals(1, fixture.generator.questions.size)
        val rejectedAttempt = Files.readString(fixture.capturePath("utf8-grounded-attempt-2"), StandardCharsets.UTF_8)
        assertTrue(rejectedAttempt.contains("MANUAL_BUDGET_REJECTED"))
        assertTrue(rejectedAttempt.contains("\"providerStageEntered\":false"))
    }

    @Test
    fun `caps query embedding and generation admissions at four without changing the HTTP cap`() {
        val fixture = fixture()
        val approved =
            listOf(
                "utf8-grounded" to QUESTION,
                "other-grounded" to "다른 한국어 질문",
                "third-grounded" to "세 번째 한국어 질문",
                "fourth-grounded" to "네 번째 한국어 질문",
                "insufficient-question" to "개인 전적 질문",
            )

        approved.dropLast(1).forEach { (id, question) ->
            fixture.mockMvc.perform(request(json(question, "16.99", "ko-KR"), id)).andExpect(status().isOk)
        }
        fixture.mockMvc
            .perform(request(json(approved.last().second, "16.99", "ko-KR"), approved.last().first))
            .andExpect(status().isTooManyRequests)

        assertEquals(4, fixture.embedding.inputs.size)
        assertEquals(4, fixture.generator.questions.size)
    }

    @Test
    fun `keeps an empty corpus distinct from a plan that blocks an active corpus embedding`() {
        val emptyCorpus = fixture(emptyUnindexedCorpus = true)

        emptyCorpus.mockMvc
            .perform(request(json("인덱스 없는 질문", "16.98", "ko-KR"), "unindexed-question"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
        assertTrue(emptyCorpus.embedding.inputs.isEmpty())
        assertTrue(emptyCorpus.generator.questions.isEmpty())

        val activeCorpus = fixture(emptyUnindexedCorpus = false)
        activeCorpus.mockMvc
            .perform(request(json("인덱스 없는 질문", "16.98", "ko-KR"), "unindexed-question"))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("MANUAL_EXECUTION_PLAN_REJECTED"))
        assertTrue(activeCorpus.embedding.inputs.isEmpty())
        assertTrue(activeCorpus.generator.questions.isEmpty())
        val capture = Files.readString(activeCorpus.capturePath("unindexed-question"), StandardCharsets.UTF_8)
        assertTrue(capture.contains("MANUAL_EXECUTION_PLAN_REJECTED"))
        assertTrue(capture.contains("\"providerStageEntered\":false"))
    }

    @Test
    fun `does not enter a provider after malformed JSON`() {
        val fixture = fixture()

        fixture.mockMvc
            .perform(
                post(PATH)
                    .header(MANUAL_ID_HEADER, "utf8-grounded")
                    .contentType(UTF8_JSON)
                    .content("{not-json".toByteArray(StandardCharsets.UTF_8)),
            ).andExpect(status().isBadRequest)

        assertTrue(fixture.embedding.inputs.isEmpty())
        assertTrue(fixture.generator.questions.isEmpty())
    }

    @Test
    fun `fails closed before request admission when the approved plan cannot be verified`() {
        val missingPlanProperties =
            RagProperties(
                enabled = true,
                answer =
                    RagAnswerProperties(
                        enabled = true,
                        manualCaptureEnabled = true,
                        manualPlanPath = temporaryDirectory.resolve("missing.properties").toString(),
                        manualPlanSha256 = "0".repeat(64),
                        manualPlanLocale = "ko-KR",
                        manualEvaluationRunId = "fixture-run",
                        manualCaptureDirectory = temporaryDirectory.resolve("capture"),
                    ),
            )

        assertFailsWith<ManualRagSmokePlanConfigurationException> {
            ManualPatchNoteAnswerEvidenceObserver(ObjectMapper(), missingPlanProperties)
        }

        val planPath = resourcePath("rag/manual-question-plan.properties")
        val mismatchedHashProperties =
            missingPlanProperties.copy(
                answer = missingPlanProperties.answer.copy(manualPlanPath = planPath.toString(), manualPlanSha256 = "f".repeat(64)),
            )
        assertFailsWith<ManualRagSmokePlanConfigurationException> {
            ManualPatchNoteAnswerEvidenceObserver(ObjectMapper(), mismatchedHashProperties)
        }
    }

    private fun fixture(emptyUnindexedCorpus: Boolean = false): Fixture {
        val planPath = resourcePath("rag/manual-question-plan.properties")
        val captureDirectory = temporaryDirectory.resolve("capture-${System.nanoTime()}")
        val properties =
            RagProperties(
                enabled = true,
                answer =
                    RagAnswerProperties(
                        enabled = true,
                        manualCaptureEnabled = true,
                        manualPlanPath = planPath.toString(),
                        manualPlanSha256 = sha256(Files.readAllBytes(planPath)),
                        manualPlanLocale = "ko-KR",
                        manualEvaluationRunId = "fixture-run",
                        manualCaptureDirectory = captureDirectory,
                    ),
            )
        val embedding = RecordingEmbeddingGateway()
        val generator = RecordingAnswerGenerator()
        val observer = ManualPatchNoteAnswerEvidenceObserver(ObjectMapper(), properties)
        val store = FixtureStore(embedding.contract, emptyUnindexedCorpus)
        val service =
            PatchNoteQuestionService(
                properties,
                AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
                PatchNoteRetrievalService(embedding, store, maximumTopK = 5, maximumEvidenceCharacters = 600),
                generator,
                evidenceObserver = observer,
                executionBudget = observer,
            )
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(PatchNoteQuestionController(service, AnalysisRateLimitKeyResolver()))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
        return Fixture(mockMvc, embedding, generator, captureDirectory.resolve("fixture-run"))
    }

    private fun request(
        body: String,
        questionId: String,
    ) = post(PATH)
        .header(MANUAL_ID_HEADER, questionId)
        .contentType(UTF8_JSON)
        .content(body.toByteArray(StandardCharsets.UTF_8))

    private fun json(
        question: String,
        patchVersion: String,
        locale: String,
    ): String =
        """{"patchVersion":"$patchVersion","locale":"$locale","question":"${question.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""

    private fun unicodeEscapedJson(question: String): String =
        json(question, "16.99", "ko-KR")
            .map { character ->
                if (character.code > 0x7f) "\\u%04x".format(character.code) else character.toString()
            }.joinToString("")

    private fun resourcePath(resource: String): Path = Path.of(requireNotNull(javaClass.classLoader.getResource(resource)).toURI())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val mockMvc: org.springframework.test.web.servlet.MockMvc,
        val embedding: RecordingEmbeddingGateway,
        val generator: RecordingAnswerGenerator,
        val captureDirectory: Path,
    ) {
        fun capturePath(questionId: String): Path = captureDirectory.resolve("$questionId.json")
    }

    private class RecordingEmbeddingGateway : EmbeddingGateway {
        override val contract = EmbeddingContract("fixture", 3)
        val inputs = mutableListOf<String>()

        override fun embed(inputs: List<String>): EmbeddingBatch {
            this.inputs += inputs
            return EmbeddingBatch(contract, inputs.map { EmbeddingVector(listOf(1f, 0f, 0f)) })
        }
    }

    private class RecordingAnswerGenerator : PatchNoteAnswerGenerator {
        val questions = mutableListOf<String>()

        override fun generate(
            request: PatchNoteAnswerGenerationRequest,
            timeout: Duration,
        ): PatchNoteAnswerGenerationResult {
            questions += request.question
            return PatchNoteAnswerGenerationResult(
                PatchNoteGeneratedAnswer(
                    PatchNoteAnswerStatus.ANSWERED,
                    listOf(PatchNoteGeneratedStatement(ANSWER, listOf("E1"))),
                    emptyList(),
                ),
            )
        }
    }

    private class FixtureStore(
        private val contract: EmbeddingContract,
        private val emptyUnindexedCorpus: Boolean,
    ) : PatchNoteDocumentStore {
        override fun findRevision(revisionFingerprint: String) = null

        override fun activateOrStore(indexed: io.github.nekke0409.lolinsight.rag.application.IndexedPatchNote) = error("not used")

        override fun findActiveEmbeddingContracts(
            patchVersion: String,
            locale: String,
        ): Set<EmbeddingContract> = if (patchVersion == "16.98" && emptyUnindexedCorpus) emptySet() else setOf(contract)

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
                    listOf("챔피언 > 한글"),
                    "근거 본문",
                    0.0,
                ),
            )
    }

    private companion object {
        const val PATH = "/api/v1/knowledge/patch-note-questions"
        const val MANUAL_ID_HEADER = "X-Rag-Manual-Question-Id"
        val UTF8_JSON = MediaType.parseMediaType("application/json; charset=utf-8")
        const val QUESTION = "한글 공백 \"따옴표\" 25.10 / 100 → 120"
        const val ANSWER = "한국어 응답 / 100 → 120"
    }
}
