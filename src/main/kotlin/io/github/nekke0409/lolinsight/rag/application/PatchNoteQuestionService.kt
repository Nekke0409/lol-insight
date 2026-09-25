package io.github.nekke0409.lolinsight.rag.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.rag.infrastructure.RagAnswerProperties
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class PatchNoteQuestionService(
    private val properties: RagProperties,
    private val rateLimiter: AnalysisGenerationRateLimiter,
    private val retrievalService: PatchNoteRetrievalService?,
    private val answerGenerator: PatchNoteAnswerGenerator?,
    private val observationRecorder: PatchNoteQuestionObservationRecorder = NoOpPatchNoteQuestionObservationRecorder,
    private val evidenceObserver: PatchNoteAnswerEvidenceObserver = NoOpPatchNoteAnswerEvidenceObserver,
    private val executionBudget: PatchNoteQuestionExecutionBudget = NoOpPatchNoteQuestionExecutionBudget,
) {
    fun answer(
        request: PatchNoteQuestionRequest,
        clientIdentity: AnalysisRateLimitKey,
    ): PatchNoteQuestionResponse {
        val startedAt = System.nanoTime()
        var retrievalAttempts = 0
        var generationAttempts = 0
        var queryEmbeddingAttempts = 0
        var queryEmbeddingInputTokens: Long? = null
        var embeddingLatency: Duration? = null
        var generationUsage: PatchNoteAnswerUsage? = null
        var generationLatency: Duration? = null
        var resultCount = 0
        var evidenceCount = 0
        var citationCount = 0
        var outcome = "FAILED"
        try {
            requireEnabled()
            request.validate(properties.answer)
            if (properties.answer.manualCaptureEnabled && request.manualQuestionId == null) {
                throw PatchNoteQuestionValidationException()
            }
            val allowance = executionBudget.beforeRequest(request)
            rateLimiter.check(clientIdentity)
            val deadline = PatchNoteQuestionDeadline.after(properties.answer.executionDeadline)
            val retrieval = requireNotNull(retrievalService) { "RAG retrieval is unavailable" }
            retrievalAttempts++
            val retrievalExecution =
                try {
                    retrieval.searchWithExecution(
                        PatchNoteSearchRequest(request.question, request.patchVersion, request.locale, properties.answer.topK),
                        deadline.nextTimeout(properties.answer.retrievalTimeout) ?: throw PatchNoteAnswerDeadlineExceededException(),
                        allowance.allowQueryEmbedding,
                    )
                } catch (_: PatchNoteRetrievalDeadlineExceededException) {
                    throw PatchNoteAnswerDeadlineExceededException()
                }
            val results = retrievalExecution.results
            queryEmbeddingAttempts = if (retrievalExecution.queryEmbeddingAttempted) 1 else 0
            queryEmbeddingInputTokens = retrievalExecution.queryEmbeddingInputTokens
            embeddingLatency = retrievalExecution.embeddingLatency
            resultCount = results.size
            deadline.requireRemaining()
            if (results.isEmpty()) return insufficient("저장된 요청 범위에서 근거를 찾지 못했습니다.").also { outcome = it.status.name }
            if (results.any {
                    it.patchVersion != request.patchVersion || it.locale != request.locale
                }
            ) {
                throw PatchNoteAnswerInvalidResponseException("retrieval result escaped the requested scope")
            }
            val evidence = buildEvidence(results)
            evidenceCount = evidence.size
            if (evidence.isEmpty()) return insufficient("검색된 근거가 요청 처리 한도 안에 포함되지 않았습니다.").also { outcome = it.status.name }
            val timeout = deadline.nextTimeout(properties.answer.generationTimeout) ?: throw PatchNoteAnswerDeadlineExceededException()
            generationAttempts++
            executionBudget.beforeGeneration(request)
            val generation =
                requireNotNull(answerGenerator) { "RAG answer generator is unavailable" }
                    .generate(PatchNoteAnswerGenerationRequest(request.question, evidence, properties.answer.maxStatements), timeout)
            generationUsage = generation.usage
            generationLatency = generation.providerLatency
            deadline.requireRemaining()
            val response = validateAndCompose(generation.answer, evidence)
            evidenceObserver.recordSafely(request, evidence, generation.answer, response)
            return response.also {
                citationCount = response.citations.size
                outcome = response.status.name
            }
        } catch (exception: PatchNoteAnswerInvalidResponseException) {
            outcome = "CITATION_OR_SCHEMA_INVALID"
            throw exception
        } catch (exception: PatchNoteAnswerIncompleteException) {
            generationUsage = exception.usage
            generationLatency = exception.providerLatency
            outcome = "GENERATION_INCOMPLETE_${exception.reason.name}"
            throw exception
        } catch (exception: PatchNoteAnswerRefusalException) {
            generationUsage = exception.usage
            generationLatency = exception.providerLatency
            outcome = "GENERATION_REFUSAL"
            throw exception
        } finally {
            observationRecorder.recordSafely(
                PatchNoteQuestionExecutionSummary(
                    outcome,
                    retrievalAttempts,
                    generationAttempts,
                    queryEmbeddingAttempts,
                    queryEmbeddingInputTokens,
                    generationUsage,
                    embeddingLatency,
                    generationLatency,
                    resultCount,
                    evidenceCount,
                    citationCount,
                    Duration.ofNanos(
                        System.nanoTime() - startedAt,
                    ),
                ),
            )
        }
    }

    private fun requireEnabled() {
        if (!properties.answer.enabled) throw PatchNoteAnswerFeatureDisabledException()
        if (!properties.enabled) throw PatchNoteAnswerConfigurationException("rag.answer.enabled requires rag.enabled=true")
    }

    private fun buildEvidence(results: List<PatchNoteSearchResult>): List<PatchNoteEvidence> {
        var remaining = properties.answer.maxEvidenceCharacters
        val seen = mutableSetOf<UUID>()
        val selected = mutableListOf<PatchNoteEvidence>()
        results.forEach { row ->
            if (!seen.add(row.chunkId)) return@forEach
            val candidate =
                PatchNoteEvidence(
                    "E${selected.size + 1}",
                    row.chunkId,
                    row.documentId,
                    row.title,
                    row.sourceUrl,
                    row.patchVersion,
                    row.locale,
                    row.revisionFingerprint,
                    row.headingPath,
                    row.evidenceText,
                )
            val size = candidate.promptCharacters()
            if (size > remaining) return@forEach
            remaining -= size
            selected += candidate
        }
        return selected
    }

    private fun validateAndCompose(
        generated: PatchNoteGeneratedAnswer,
        evidence: List<PatchNoteEvidence>,
    ): PatchNoteQuestionResponse {
        val available = evidence.associateBy(PatchNoteEvidence::evidenceId)
        if (generated.limitations.any { it.isBlank() }) throw PatchNoteAnswerInvalidResponseException("blank limitation")
        return when (generated.status) {
            PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE -> {
                if (generated.statements.isNotEmpty()) {
                    throw PatchNoteAnswerInvalidResponseException(
                        "insufficient response contains statements",
                    )
                }
                PatchNoteQuestionResponse(generated.status, null, emptyList(), emptyList(), generated.limitations)
            }
            PatchNoteAnswerStatus.ANSWERED -> {
                if (generated.statements.isEmpty() ||
                    generated.statements.size > properties.answer.maxStatements
                ) {
                    throw PatchNoteAnswerInvalidResponseException("invalid statement count")
                }
                val cited = linkedSetOf<String>()
                generated.statements.forEach { statement ->
                    if (statement.text.isBlank() || statement.evidenceIds.isEmpty() || statement.evidenceIds.any { it !in available }) {
                        throw PatchNoteAnswerInvalidResponseException("invalid citation")
                    }
                    cited += statement.evidenceIds
                }
                val citations = cited.map { available.getValue(it).toCitation() }
                PatchNoteQuestionResponse(
                    generated.status,
                    generated.statements.joinToString("\n") {
                        it.text
                    },
                    generated.statements,
                    citations,
                    generated.limitations,
                )
            }
        }
    }

    private fun insufficient(message: String) =
        PatchNoteQuestionResponse(PatchNoteAnswerStatus.INSUFFICIENT_EVIDENCE, null, emptyList(), emptyList(), listOf(message))
}

data class PatchNoteQuestionRequest(
    val patchVersion: String,
    val locale: String,
    val question: String,
    val manualQuestionId: String? = null,
) {
    fun validate(properties: RagAnswerProperties) {
        if (patchVersion.isBlank() ||
            locale.isBlank() ||
            question.isBlank() ||
            question.length > properties.maxQuestionCharacters ||
            (manualQuestionId != null && !MANUAL_QUESTION_ID.matches(manualQuestionId))
        ) {
            throw PatchNoteQuestionValidationException()
        }
    }

    private companion object {
        val MANUAL_QUESTION_ID = Regex("[a-z0-9-]{1,80}")
    }
}

enum class PatchNoteAnswerStatus { ANSWERED, INSUFFICIENT_EVIDENCE }

data class PatchNoteEvidence(
    val evidenceId: String,
    val chunkId: UUID,
    val documentId: UUID,
    val title: String,
    val sourceUrl: String,
    val patchVersion: String,
    val locale: String,
    val revisionFingerprint: String,
    val headingPath: List<String>,
    val evidenceText: String,
) {
    fun promptCharacters(): Int =
        listOf(
            evidenceId,
            title,
            sourceUrl,
            patchVersion,
            locale,
            revisionFingerprint,
            headingPath.joinToString(" > "),
            evidenceText,
        ).sumOf(String::length)

    fun toCitation() =
        PatchNoteCitation(
            evidenceId,
            sourceUrl,
            title,
            patchVersion,
            locale,
            headingPath,
            chunkId,
            documentId,
            revisionFingerprint,
            evidenceText,
        )
}

data class PatchNoteAnswerGenerationRequest(
    val question: String,
    val evidence: List<PatchNoteEvidence>,
    val maxStatements: Int,
)

interface PatchNoteAnswerGenerator {
    fun generate(
        request: PatchNoteAnswerGenerationRequest,
        timeout: Duration,
    ): PatchNoteAnswerGenerationResult
}

data class PatchNoteAnswerGenerationResult(
    val answer: PatchNoteGeneratedAnswer,
    val usage: PatchNoteAnswerUsage? = null,
    val providerLatency: Duration? = null,
)

data class PatchNoteAnswerUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0 && totalTokens >= 0) { "token usage must not be negative" }
    }
}

data class PatchNoteGeneratedAnswer(
    val status: PatchNoteAnswerStatus,
    val statements: List<PatchNoteGeneratedStatement>,
    val limitations: List<String>,
)

data class PatchNoteGeneratedStatement(
    val text: String,
    val evidenceIds: List<String>,
)

data class PatchNoteQuestionResponse(
    val status: PatchNoteAnswerStatus,
    val answer: String?,
    val statements: List<PatchNoteGeneratedStatement>,
    val citations: List<PatchNoteCitation>,
    val limitations: List<String>,
)

data class PatchNoteCitation(
    val evidenceId: String,
    val sourceUrl: String,
    val title: String,
    val patchVersion: String,
    val locale: String,
    val headingPath: List<String>,
    val chunkId: UUID,
    val documentId: UUID,
    val revisionFingerprint: String,
    val evidenceText: String,
)

class PatchNoteAnswerFeatureDisabledException : RuntimeException("RAG patch-note answer is disabled")

class PatchNoteQuestionValidationException : RuntimeException("Patch-note question is invalid")

class PatchNoteAnswerConfigurationException(
    message: String,
) : RuntimeException(message)

class PatchNoteAnswerInvalidResponseException(
    message: String,
) : RuntimeException(message)

class PatchNoteAnswerDeadlineExceededException : RuntimeException("Patch-note answer deadline exceeded")

class ManualRagSmokeBudgetExceededException(
    message: String,
) : RuntimeException(message)

class PatchNoteAnswerIncompleteException(
    val reason: PatchNoteAnswerIncompleteReason,
    val usage: PatchNoteAnswerUsage?,
    val providerLatency: Duration?,
) : RuntimeException("RAG answer response was incomplete")

enum class PatchNoteAnswerIncompleteReason {
    MAX_OUTPUT_TOKENS,
    MAX_MESSAGES,
    CONTENT_FILTER,
    STEERED,
    UNKNOWN,
}

class PatchNoteAnswerRefusalException(
    val usage: PatchNoteAnswerUsage?,
    val providerLatency: Duration?,
) : RuntimeException("RAG answer was refused")

data class PatchNoteQuestionExecutionSummary(
    val outcome: String,
    val retrievalAttempts: Int,
    val generationAttempts: Int,
    val queryEmbeddingAttempts: Int,
    val queryEmbeddingInputTokens: Long?,
    val generationUsage: PatchNoteAnswerUsage?,
    val embeddingLatency: Duration?,
    val generationLatency: Duration?,
    val retrievalResultCount: Int,
    val evidenceCount: Int,
    val citationCount: Int,
    val duration: Duration,
)

interface PatchNoteQuestionObservationRecorder {
    fun record(summary: PatchNoteQuestionExecutionSummary)
}

/** Manual smoke may opt in to this boundary; normal observability never receives raw question, evidence, or answer. */
interface PatchNoteAnswerEvidenceObserver {
    fun record(
        request: PatchNoteQuestionRequest,
        evidence: List<PatchNoteEvidence>,
        answer: PatchNoteGeneratedAnswer,
        response: PatchNoteQuestionResponse,
    )
}

interface PatchNoteQuestionExecutionBudget {
    fun beforeRequest(request: PatchNoteQuestionRequest): PatchNoteQuestionExecutionAllowance

    fun beforeGeneration(request: PatchNoteQuestionRequest)
}

data class PatchNoteQuestionExecutionAllowance(
    val allowQueryEmbedding: Boolean,
)

object NoOpPatchNoteQuestionExecutionBudget : PatchNoteQuestionExecutionBudget {
    override fun beforeRequest(request: PatchNoteQuestionRequest): PatchNoteQuestionExecutionAllowance =
        PatchNoteQuestionExecutionAllowance(true)

    override fun beforeGeneration(request: PatchNoteQuestionRequest) = Unit
}

object NoOpPatchNoteAnswerEvidenceObserver : PatchNoteAnswerEvidenceObserver {
    override fun record(
        request: PatchNoteQuestionRequest,
        evidence: List<PatchNoteEvidence>,
        answer: PatchNoteGeneratedAnswer,
        response: PatchNoteQuestionResponse,
    ) = Unit
}

object NoOpPatchNoteQuestionObservationRecorder : PatchNoteQuestionObservationRecorder {
    override fun record(summary: PatchNoteQuestionExecutionSummary) = Unit
}

private fun PatchNoteQuestionObservationRecorder.recordSafely(summary: PatchNoteQuestionExecutionSummary) {
    try {
        record(summary)
    } catch (_: Exception) {
        // Observation must not change the response.
    }
}

private fun PatchNoteAnswerEvidenceObserver.recordSafely(
    request: PatchNoteQuestionRequest,
    evidence: List<PatchNoteEvidence>,
    answer: PatchNoteGeneratedAnswer,
    response: PatchNoteQuestionResponse,
) {
    try {
        record(request, evidence, answer, response)
    } catch (_: Exception) {
        // Manual evidence capture must not cause a second provider request or change the response.
    }
}

private class PatchNoteQuestionDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun nextTimeout(maximum: Duration): Duration? =
        (deadlineNanos - System.nanoTime()).takeIf { it > 0 }?.let { minOf(maximum, Duration.ofNanos(it)) }

    fun requireRemaining() {
        if (System.nanoTime() >= deadlineNanos) throw PatchNoteAnswerDeadlineExceededException()
    }

    companion object {
        fun after(duration: Duration) = PatchNoteQuestionDeadline(Math.addExact(System.nanoTime(), duration.toNanos()))
    }
}
