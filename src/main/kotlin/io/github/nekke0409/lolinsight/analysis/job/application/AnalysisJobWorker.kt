package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponseStatus
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import org.springframework.stereotype.Component

@Component
class AnalysisJobWorker(
    private val lifecycleService: AnalysisJobLifecycleService,
    private val playerAnalysisService: PlayerAnalysisService,
    private val failureCodeMapper: AnalysisJobFailureCodeMapper,
    private val inFlightRegistry: AnalysisJobInFlightRegistry,
) {
    fun process(command: AnalysisJobCommand) {
        try {
            if (!lifecycleService.markRunningIfPending(command.jobId)) {
                return
            }

            val response =
                playerAnalysisService.analyze(
                    gameName = command.gameName,
                    tagLine = command.tagLine,
                    start = command.start,
                    count = command.count,
                )
            when (response.status) {
                PlayerAnalysisResponseStatus.ANALYZED -> {
                    completeSucceeded(
                        command.jobId,
                        requireNotNull(response.analysis) { "ANALYZED response must contain an analysis result" },
                        command.dedupeKey,
                    )
                }
                PlayerAnalysisResponseStatus.UNRANKED -> {
                    completeFailed(command.jobId, AnalysisJobFailureCode.UNRANKED, command.dedupeKey)
                }
                PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA -> {
                    completeFailed(
                        command.jobId,
                        AnalysisJobFailureCode.INSUFFICIENT_COMPARISON_DATA,
                        command.dedupeKey,
                    )
                }
            }
        } catch (exception: Exception) {
            markFailed(command, failureCodeMapper.map(exception))
        }
    }

    private fun completeSucceeded(
        jobId: java.util.UUID,
        result: io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult,
        dedupeKey: AnalysisJobDedupeKey?,
    ) {
        if (lifecycleService.markSucceededIfRunning(jobId, result)) {
            dedupeKey?.let { inFlightRegistry.remove(it, jobId) }
        }
    }

    private fun completeFailed(
        jobId: java.util.UUID,
        failureCode: AnalysisJobFailureCode,
        dedupeKey: AnalysisJobDedupeKey?,
    ) {
        if (lifecycleService.markFailedIfRunning(jobId, failureCode)) {
            dedupeKey?.let { inFlightRegistry.remove(it, jobId) }
        }
    }

    private fun markFailed(
        command: AnalysisJobCommand,
        failureCode: AnalysisJobFailureCode,
    ) {
        try {
            completeFailed(command.jobId, failureCode, command.dedupeKey)
        } catch (_: Exception) {
            // A persistence outage cannot be repaired by this same-process v0.1 worker.
        }
    }
}
