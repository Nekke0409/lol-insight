package io.github.nekke0409.lolinsight.rag.infrastructure

import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeBudgetExceededException
import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeInputMismatchException
import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeInputMismatchReason
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerEvidenceObserver
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidence
import io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedAnswer
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionAllowance
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionBudget
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionSummary
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionRequest
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Local-only guard and artifact writer for one explicitly approved manual evaluation run.
 *
 * The plan is loaded once during bean construction and compared with a configured SHA-256. The client never supplies
 * an expected hash, and the accepted DTO question is never rewritten from the plan.
 */
@Component
@ConditionalOnProperty(prefix = "rag.answer", name = ["manual-capture-enabled"], havingValue = "true")
class ManualPatchNoteAnswerEvidenceObserver(
    private val objectMapper: ObjectMapper,
    properties: RagProperties,
) : PatchNoteAnswerEvidenceObserver,
    PatchNoteQuestionExecutionBudget {
    private val settings = ManualPatchNoteAnswerEvaluationSettings.from(properties.answer)
    private val plan = ManualPatchNoteQuestionPlan.load(settings)
    private val deliveredAnswers = mutableMapOf<String, DeliveredAnswer>()
    private val requestedQuestionIds = linkedSetOf<String>()
    private val queryEmbeddingQuestionIds = linkedSetOf<String>()
    private val generationQuestionIds = linkedSetOf<String>()
    private val artifactAttempts = mutableMapOf<String, Int>()
    private var httpRequestCount = 0

    @Synchronized
    override fun beforeRequest(request: PatchNoteQuestionRequest): PatchNoteQuestionExecutionAllowance {
        if (httpRequestCount >= MAX_HTTP_REQUESTS) throw ManualRagSmokeBudgetExceededException("HTTP request budget exhausted")
        httpRequestCount += 1

        val questionId =
            request.manualQuestionId
                ?: throw ManualRagSmokeInputMismatchException(ManualRagSmokeInputMismatchReason.MISSING_QUESTION_ID)
        val expected =
            plan.question(questionId)
                ?: throw ManualRagSmokeInputMismatchException(ManualRagSmokeInputMismatchReason.UNAPPROVED_QUESTION_ID)
        if (!requestedQuestionIds.add(questionId)) {
            throw ManualRagSmokeBudgetExceededException("question id was already admitted for this evaluation run")
        }
        if (request.patchVersion != expected.patchVersion) {
            throw ManualRagSmokeInputMismatchException(ManualRagSmokeInputMismatchReason.PATCH_VERSION_MISMATCH)
        }
        if (request.locale != expected.locale) {
            throw ManualRagSmokeInputMismatchException(ManualRagSmokeInputMismatchReason.LOCALE_MISMATCH)
        }
        if (request.question != expected.question) {
            throw ManualRagSmokeInputMismatchException(ManualRagSmokeInputMismatchReason.QUESTION_MISMATCH)
        }
        if (expected.kind != ManualPatchNoteQuestionKind.UNINDEXED &&
            (!queryEmbeddingQuestionIds.add(questionId) || queryEmbeddingQuestionIds.size > MAX_QUERY_EMBEDDING_REQUESTS)
        ) {
            throw ManualRagSmokeBudgetExceededException("query embedding budget exhausted")
        }
        return PatchNoteQuestionExecutionAllowance(expected.kind != ManualPatchNoteQuestionKind.UNINDEXED)
    }

    @Synchronized
    override fun beforeGeneration(request: PatchNoteQuestionRequest) {
        val questionId = requireNotNull(request.manualQuestionId) { "manual execution requires a question id" }
        val expected = plan.question(questionId) ?: error("admitted question must be present in the plan")
        if (expected.kind == ManualPatchNoteQuestionKind.UNINDEXED) {
            throw ManualRagSmokeBudgetExceededException("generation is not allowed for an unindexed plan question")
        }
        if (!generationQuestionIds.add(questionId) || generationQuestionIds.size > MAX_GENERATION_REQUESTS) {
            throw ManualRagSmokeBudgetExceededException("generation budget exhausted")
        }
    }

    @Synchronized
    override fun record(
        request: PatchNoteQuestionRequest,
        evidence: List<PatchNoteEvidence>,
        answer: PatchNoteGeneratedAnswer,
        response: PatchNoteQuestionResponse,
    ) {
        val questionId = requireNotNull(request.manualQuestionId) { "manual capture requires a question id" }
        deliveredAnswers[questionId] = DeliveredAnswer(evidence, answer, response)
    }

    @Synchronized
    override fun recordExecution(
        request: PatchNoteQuestionRequest,
        summary: PatchNoteQuestionExecutionSummary,
    ) {
        val expected = request.manualQuestionId?.let(plan::question)
        val actualFingerprint = ManualPatchNoteInputFingerprint.from(request.question)
        val delivered = request.manualQuestionId?.let(deliveredAnswers::remove)
        val questionId =
            request.manualQuestionId?.takeIf(MANUAL_QUESTION_ID::matches)
                ?: "rejected-${actualFingerprint.sha256.take(12)}"
        val artifactAttempt = (artifactAttempts[questionId] ?: 0) + 1
        val capture =
            ManualPatchNoteAnswerEvaluationCapture(
                evaluationRunId = settings.evaluationRunId,
                questionId = request.manualQuestionId,
                artifactAttempt = artifactAttempt,
                planSha256 = plan.sha256,
                expectedQuestionSha256 = expected?.fingerprint?.sha256,
                expectedQuestionUtf8Bytes = expected?.fingerprint?.utf8Bytes,
                actualQuestionSha256 = actualFingerprint.sha256,
                actualQuestionUtf8Bytes = actualFingerprint.utf8Bytes,
                actualQuestionCodePoints = actualFingerprint.codePoints,
                inputMatches =
                    expected != null &&
                        request.patchVersion == expected.patchVersion &&
                        request.locale == expected.locale &&
                        request.question == expected.question,
                rejectionReason = summary.outcome.takeIf { it.startsWith("MANUAL_") },
                providerStageEntered = summary.queryEmbeddingAttempts > 0 || summary.generationAttempts > 0,
                outcome = summary.outcome,
                retrievalAttempts = summary.retrievalAttempts,
                queryEmbeddingAttempts = summary.queryEmbeddingAttempts,
                generationAttempts = summary.generationAttempts,
                evidence = delivered?.evidence?.map(ManualPatchNoteEvidence::from).orEmpty(),
                statements = delivered?.answer?.statements.orEmpty(),
                status = delivered?.answer?.status?.name,
                citations = delivered?.response?.citations.orEmpty(),
            )
        writeCapture(questionId, artifactAttempt, capture)
    }

    private fun writeCapture(
        questionId: String,
        artifactAttempt: Int,
        capture: ManualPatchNoteAnswerEvaluationCapture,
    ) {
        Files.createDirectories(settings.captureDirectory)
        val fileName = if (artifactAttempt == 1) "$questionId.json" else "$questionId-attempt-$artifactAttempt.json"
        val target = settings.captureDirectory.resolve(fileName)
        if (Files.exists(target)) throw IllegalStateException("manual evaluation artifact already exists")
        val temporary = Files.createTempFile(settings.captureDirectory, "$questionId-", ".tmp")
        try {
            objectMapper.writeValue(temporary.toFile(), capture)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            artifactAttempts[questionId] = artifactAttempt
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val MAX_HTTP_REQUESTS = 5
        const val MAX_QUERY_EMBEDDING_REQUESTS = 4
        const val MAX_GENERATION_REQUESTS = 4
        val MANUAL_QUESTION_ID = Regex("[a-z0-9-]{1,80}")
    }
}

private data class DeliveredAnswer(
    val evidence: List<PatchNoteEvidence>,
    val answer: PatchNoteGeneratedAnswer,
    val response: PatchNoteQuestionResponse,
)

data class ManualPatchNoteAnswerEvaluationCapture(
    val evaluationRunId: String,
    val questionId: String?,
    val artifactAttempt: Int,
    val planSha256: String,
    val expectedQuestionSha256: String?,
    val expectedQuestionUtf8Bytes: Int?,
    val actualQuestionSha256: String,
    val actualQuestionUtf8Bytes: Int,
    val actualQuestionCodePoints: Int,
    val inputMatches: Boolean,
    val rejectionReason: String?,
    val providerStageEntered: Boolean,
    val outcome: String,
    val retrievalAttempts: Int,
    val queryEmbeddingAttempts: Int,
    val generationAttempts: Int,
    val evidence: List<ManualPatchNoteEvidence>,
    val statements: List<io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedStatement>,
    val status: String?,
    val citations: List<io.github.nekke0409.lolinsight.rag.application.PatchNoteCitation>,
)

data class ManualPatchNoteEvidence(
    val evidenceId: String,
    val chunkId: String,
    val documentId: String,
    val revisionFingerprint: String,
    val headingPath: List<String>,
    val textSha256: String,
    val excerpt: String,
) {
    companion object {
        fun from(evidence: PatchNoteEvidence): ManualPatchNoteEvidence =
            ManualPatchNoteEvidence(
                evidence.evidenceId,
                evidence.chunkId.toString(),
                evidence.documentId.toString(),
                evidence.revisionFingerprint,
                evidence.headingPath,
                sha256(evidence.evidenceText),
                evidence.evidenceText.take(600),
            )
    }
}

internal data class ManualPatchNoteInputFingerprint(
    val sha256: String,
    val utf8Bytes: Int,
    val codePoints: Int,
) {
    companion object {
        fun from(value: String): ManualPatchNoteInputFingerprint {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return ManualPatchNoteInputFingerprint(
                sha256(bytes),
                bytes.size,
                value.codePointCount(0, value.length),
            )
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))
