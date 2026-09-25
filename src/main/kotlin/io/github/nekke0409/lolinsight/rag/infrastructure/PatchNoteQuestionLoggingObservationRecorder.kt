package io.github.nekke0409.lolinsight.rag.infrastructure

import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionExecutionSummary
import io.github.nekke0409.lolinsight.rag.application.PatchNoteQuestionObservationRecorder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Logs only bounded operational counters; never question, evidence, model output, URL, vector, or provider payload. */
@Component
class PatchNoteQuestionLoggingObservationRecorder : PatchNoteQuestionObservationRecorder {
    override fun record(summary: PatchNoteQuestionExecutionSummary) {
        logger.info(
            "rag_patch_note_question outcome={} retrievalAttempts={} queryEmbeddingAttempts={} queryEmbeddingInputTokens={} embeddingLatencyMs={} generationAttempts={} generationInputTokens={} generationOutputTokens={} generationTotalTokens={} generationLatencyMs={} retrievalResults={} evidence={} citations={} durationMs={}",
            summary.outcome,
            summary.retrievalAttempts,
            summary.queryEmbeddingAttempts,
            summary.queryEmbeddingInputTokens,
            summary.embeddingLatency?.toMillis(),
            summary.generationAttempts,
            summary.generationUsage?.inputTokens,
            summary.generationUsage?.outputTokens,
            summary.generationUsage?.totalTokens,
            summary.generationLatency?.toMillis(),
            summary.retrievalResultCount,
            summary.evidenceCount,
            summary.citationCount,
            summary.duration.toMillis(),
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PatchNoteQuestionLoggingObservationRecorder::class.java)
    }
}
