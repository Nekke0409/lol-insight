package io.github.nekke0409.lolinsight.rag.infrastructure

import io.github.nekke0409.lolinsight.rag.application.ManualRagSmokeBudgetExceededException
import io.github.nekke0409.lolinsight.rag.application.PatchNoteAnswerEvidenceObserver
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidence
import io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedAnswer
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionAllowance
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionBudget
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionRequest
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Opt-in, local-only evaluation artifact. It is deliberately outside normal logs and writes only data supplied to the
 * generator (with a bounded text excerpt), not a later database reread or a provider raw response.
 */
@Component
@ConditionalOnProperty(prefix = "rag.answer", name = ["manual-capture-enabled"], havingValue = "true")
class ManualPatchNoteAnswerEvidenceObserver(
    private val objectMapper: ObjectMapper,
) : PatchNoteAnswerEvidenceObserver,
    PatchNoteQuestionExecutionBudget {
    override fun record(
        request: PatchNoteQuestionRequest,
        evidence: List<PatchNoteEvidence>,
        answer: PatchNoteGeneratedAnswer,
        response: PatchNoteQuestionResponse,
    ) {
        val questionId = requireNotNull(request.manualQuestionId) { "manual capture requires X-Rag-Manual-Question-Id" }
        Files.createDirectories(captureDirectory)
        val target = captureDirectory.resolve("$questionId.json")
        val temporary = Files.createTempFile(captureDirectory, "$questionId-", ".tmp")
        try {
            objectMapper.writeValue(
                temporary.toFile(),
                ManualPatchNoteAnswerCapture(
                    questionId = questionId,
                    patchVersion = request.patchVersion,
                    locale = request.locale,
                    question = request.question,
                    evidence = evidence.map(ManualPatchNoteEvidence::from),
                    statements = answer.statements,
                    status = answer.status.name,
                    citations = response.citations,
                ),
            )
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    override fun beforeRequest(request: PatchNoteQuestionRequest): PatchNoteQuestionExecutionAllowance {
        val questionId = requireNotNull(request.manualQuestionId) { "manual execution budget requires a question id" }
        val policy = QUESTION_POLICIES[questionId] ?: throw ManualRagSmokeBudgetExceededException("unapproved question id")
        if (request.patchVersion != policy.patchVersion || request.locale != "ko-KR" || !requestedQuestionIds.add(questionId)) {
            throw ManualRagSmokeBudgetExceededException("question budget exhausted or scope changed")
        }
        if (requestedQuestionIds.size > MAX_HTTP_REQUESTS) throw ManualRagSmokeBudgetExceededException("HTTP request budget exhausted")
        return PatchNoteQuestionExecutionAllowance(policy.allowQueryEmbedding)
    }

    @Synchronized
    override fun beforeGeneration(request: PatchNoteQuestionRequest) {
        val questionId = requireNotNull(request.manualQuestionId) { "manual execution budget requires a question id" }
        if (!QUESTION_POLICIES.getValue(questionId).allowGeneration || !generationQuestionIds.add(questionId)) {
            throw ManualRagSmokeBudgetExceededException("generation budget exhausted")
        }
        if (generationQuestionIds.size > MAX_GENERATION_REQUESTS) throw ManualRagSmokeBudgetExceededException("generation budget exhausted")
    }

    private companion object {
        val captureDirectory: Path = Path.of(".local", "rag", "answer-evaluation")
        const val MAX_HTTP_REQUESTS = 5
        const val MAX_GENERATION_REQUESTS = 4
        val requestedQuestionIds = linkedSetOf<String>()
        val generationQuestionIds = linkedSetOf<String>()
        val QUESTION_POLICIES =
            mapOf(
                "unindexed-patch-25-09" to ManualQuestionPolicy("25.09", allowQueryEmbedding = false, allowGeneration = false),
                "chogath-mid-top-rationale" to ManualQuestionPolicy("25.10", allowQueryEmbedding = true, allowGeneration = true),
                "lulu-wild-growth-cooldown" to ManualQuestionPolicy("25.10", allowQueryEmbedding = true, allowGeneration = true),
                "fiddlesticks-terrify-duration" to ManualQuestionPolicy("25.10", allowQueryEmbedding = true, allowGeneration = true),
                "personal-win-rate-decline" to ManualQuestionPolicy("25.10", allowQueryEmbedding = true, allowGeneration = true),
            )
    }
}

private data class ManualQuestionPolicy(
    val patchVersion: String,
    val allowQueryEmbedding: Boolean,
    val allowGeneration: Boolean,
)

data class ManualPatchNoteAnswerCapture(
    val questionId: String,
    val patchVersion: String,
    val locale: String,
    val question: String,
    val evidence: List<ManualPatchNoteEvidence>,
    val statements: List<io.github.nekke0409.lolinsight.rag.application.PatchNoteGeneratedStatement>,
    val status: String,
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

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
