package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponseStatus
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import org.springframework.stereotype.Component

@Component
class AnalysisJobWorker(
    private val lifecycleService: AnalysisJobLifecycleService,
    private val playerAnalysisService: PlayerAnalysisService,
    private val failureCodeMapper: AnalysisJobFailureCodeMapper,
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
                    lifecycleService.markSucceededIfRunning(
                        command.jobId,
                        requireNotNull(response.analysis) { "ANALYZED response must contain an analysis result" },
                    )
                }
                PlayerAnalysisResponseStatus.UNRANKED -> {
                    lifecycleService.markFailedIfRunning(command.jobId, AnalysisJobFailureCode.UNRANKED)
                }
                PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA -> {
                    lifecycleService.markFailedIfRunning(
                        command.jobId,
                        AnalysisJobFailureCode.INSUFFICIENT_COMPARISON_DATA,
                    )
                }
            }
        } catch (exception: Exception) {
            markFailed(command.jobId, failureCodeMapper.map(exception))
        }
    }

    private fun markFailed(
        jobId: java.util.UUID,
        failureCode: AnalysisJobFailureCode,
    ) {
        try {
            lifecycleService.markFailedIfRunning(jobId, failureCode)
        } catch (_: Exception) {
            // A persistence outage cannot be repaired by this same-process v0.1 worker.
        }
    }
}
