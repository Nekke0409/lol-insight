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
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Comparator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
@SpringBootTest(
    classes = [PatchNoteQuestionWindowsUtf8HttpIntegrationTest.HttpTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PatchNoteQuestionWindowsUtf8HttpIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `PowerShell sends the approved UTF-8 body through localhost and preserves the Korean response artifact`() {
        val planPath = resourcePath("rag/manual-question-plan.properties")
        val clientDirectory = temporaryDirectory.resolve("client")
        val result =
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                Path.of("scripts", "rag", "Invoke-PatchNoteAnswerEvaluation.ps1").toAbsolutePath().toString(),
                "-PlanPath",
                planPath.toString(),
                "-QuestionId",
                QUESTION_ID,
                "-ExpectedPlanSha256",
                planHash(),
                "-Endpoint",
                "http://127.0.0.1:$port$PATH",
                "-RunId",
                RUN_ID,
                "-OutputDirectory",
                clientDirectory.toString(),
                "-Locale",
                "ko-KR",
            ).redirectErrorStream(true).start()
        val output = result.inputStream.readBytes().toString(StandardCharsets.UTF_8)

        assertEquals(0, result.waitFor(), output)
        val clientResponse = Files.readString(clientDirectory.resolve("$QUESTION_ID.response.json"), StandardCharsets.UTF_8)
        assertTrue(clientResponse.contains(ANSWER))
        assertTrue(clientResponse.contains(HEADING))
        val clientTransmission = Files.readString(clientDirectory.resolve("$QUESTION_ID.transmission.json"), StandardCharsets.UTF_8)
        assertTrue(clientTransmission.contains("\"responseStatus\":200"))
        assertTrue(clientTransmission.contains(planHash()))

        assertEquals(listOf(QUESTION), httpRecordingEmbeddingGateway.inputs)
        assertEquals(listOf(QUESTION), httpRecordingAnswerGenerator.questions)
        val serverCapture = Files.readString(captureDirectory().resolve("$QUESTION_ID.json"), StandardCharsets.UTF_8)
        assertTrue(serverCapture.contains("\"inputMatches\":true"))
        assertTrue(serverCapture.contains("\"providerStageEntered\":true"))
        assertTrue(serverCapture.contains(questionHash()))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class,
            FlywayAutoConfiguration::class,
            HibernateJpaAutoConfiguration::class,
            DataRedisAutoConfiguration::class,
        ],
    )
    @Import(HttpTestConfiguration::class, GlobalExceptionHandler::class)
    class HttpTestApplication

    @TestConfiguration(proxyBeanMethods = false)
    class HttpTestConfiguration {
        @Bean
        fun recordingEmbeddingGateway(): RecordingEmbeddingGateway = httpRecordingEmbeddingGateway

        @Bean
        fun recordingAnswerGenerator(): RecordingAnswerGenerator = httpRecordingAnswerGenerator

        @Bean
        fun manualObserver(objectMapper: ObjectMapper): ManualPatchNoteAnswerEvidenceObserver =
            ManualPatchNoteAnswerEvidenceObserver(objectMapper, manualProperties())

        @Bean
        fun questionService(
            embeddingGateway: RecordingEmbeddingGateway,
            answerGenerator: RecordingAnswerGenerator,
            manualObserver: ManualPatchNoteAnswerEvidenceObserver,
        ): PatchNoteQuestionService =
            PatchNoteQuestionService(
                manualProperties(),
                AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(capacity = 10), Clock.systemUTC()),
                PatchNoteRetrievalService(embeddingGateway, FixtureStore(embeddingGateway.contract), 5, 600),
                answerGenerator,
                evidenceObserver = manualObserver,
                executionBudget = manualObserver,
            )

        @Bean
        fun questionController(questionService: PatchNoteQuestionService): PatchNoteQuestionController =
            PatchNoteQuestionController(questionService, AnalysisRateLimitKeyResolver())
    }

    class RecordingEmbeddingGateway : EmbeddingGateway {
        override val contract = EmbeddingContract("fixture", 3)
        val inputs = mutableListOf<String>()

        override fun embed(inputs: List<String>): EmbeddingBatch {
            this.inputs += inputs
            return EmbeddingBatch(contract, inputs.map { EmbeddingVector(listOf(1f, 0f, 0f)) })
        }
    }

    class RecordingAnswerGenerator : PatchNoteAnswerGenerator {
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

    class FixtureStore(
        private val contract: EmbeddingContract,
    ) : PatchNoteDocumentStore {
        override fun findRevision(revisionFingerprint: String) = null

        override fun activateOrStore(indexed: io.github.nekke0409.lolinsight.rag.application.IndexedPatchNote) = error("not used")

        override fun findActiveEmbeddingContracts(
            patchVersion: String,
            locale: String,
        ): Set<EmbeddingContract> = setOf(contract)

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
                    listOf(HEADING),
                    "근거 본문",
                    0.0,
                ),
            )
    }

    companion object {
        const val PATH = "/api/v1/knowledge/patch-note-questions"
        const val QUESTION_ID = "utf8-grounded"
        const val RUN_ID = "windows-http-fixture"
        const val QUESTION = "한글 공백 \"따옴표\" 25.10 / 100 → 120"
        const val ANSWER = "한국어 응답 / 100 → 120"
        const val HEADING = "챔피언 > 한글"

        val captureRoot: Path = Files.createTempDirectory("rag-answer-windows-http-")
        val httpRecordingEmbeddingGateway = RecordingEmbeddingGateway()
        val httpRecordingAnswerGenerator = RecordingAnswerGenerator()

        fun manualProperties(): RagProperties =
            RagProperties(
                enabled = true,
                answer =
                    RagAnswerProperties(
                        enabled = true,
                        manualCaptureEnabled = true,
                        manualPlanPath = resourcePath("rag/manual-question-plan.properties").toString(),
                        manualPlanSha256 = planHash(),
                        manualPlanLocale = "ko-KR",
                        manualEvaluationRunId = RUN_ID,
                        manualCaptureDirectory = captureRoot,
                    ),
            )

        fun planHash(): String = sha256(Files.readAllBytes(resourcePath("rag/manual-question-plan.properties")))

        fun questionHash(): String = sha256(QUESTION.toByteArray(StandardCharsets.UTF_8))

        fun captureDirectory(): Path = captureRoot.resolve(RUN_ID)

        fun resourcePath(resource: String): Path =
            Path.of(requireNotNull(PatchNoteQuestionWindowsUtf8HttpIntegrationTest::class.java.classLoader.getResource(resource)).toURI())

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        @AfterAll
        @JvmStatic
        fun removeTemporaryCaptureDirectory() {
            Files.walk(captureRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
