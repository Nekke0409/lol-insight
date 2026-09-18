package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponse
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponseStatus
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldownException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class AnalysisJobWorkerTest {
    private val lifecycleService = mock(AnalysisJobLifecycleService::class.java)
    private val playerAnalysisService = mock(PlayerAnalysisService::class.java)
    private val failureCodeMapper = AnalysisJobFailureCodeMapper()
    private val inFlightRegistry = mock(AnalysisJobInFlightRegistry::class.java)
    private val worker = AnalysisJobWorker(lifecycleService, playerAnalysisService, failureCodeMapper, inFlightRegistry)

    @Test
    fun `runs the existing PlayerAnalysisService and persists its analysis result`() {
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(true)
        `when`(lifecycleService.markSucceededIfRunning(COMMAND.jobId, RESULT)).thenReturn(true)
        `when`(playerAnalysisService.analyze(COMMAND.gameName, COMMAND.tagLine, COMMAND.start, COMMAND.count))
            .thenReturn(PlayerAnalysisResponse(PlayerAnalysisResponseStatus.ANALYZED, RESULT))

        worker.process(COMMAND)

        verify(lifecycleService).markRunningIfPending(COMMAND.jobId)
        verify(playerAnalysisService).analyze(COMMAND.gameName, COMMAND.tagLine, COMMAND.start, COMMAND.count)
        verify(lifecycleService).markSucceededIfRunning(COMMAND.jobId, RESULT)
        verify(inFlightRegistry).remove(requireNotNull(COMMAND.dedupeKey), COMMAND.jobId)
    }

    @Test
    fun `does not execute a duplicate or terminal job`() {
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(false)

        worker.process(COMMAND)

        verify(lifecycleService).markRunningIfPending(COMMAND.jobId)
        verifyNoInteractions(playerAnalysisService)
    }

    @Test
    fun `stores only a safe failure code when the analysis service fails`() {
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(true)
        `when`(lifecycleService.markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.RATE_LIMITED)).thenReturn(true)
        `when`(playerAnalysisService.analyze(COMMAND.gameName, COMMAND.tagLine, COMMAND.start, COMMAND.count))
            .thenThrow(PlayerAnalysisRateLimitException(IllegalStateException("provider response body")))

        worker.process(COMMAND)

        verify(lifecycleService).markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.RATE_LIMITED)
        verify(inFlightRegistry).remove(requireNotNull(COMMAND.dedupeKey), COMMAND.jobId)
    }

    @Test
    fun `classifies a locally blocked Riot request as rate limited`() {
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(true)
        `when`(lifecycleService.markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.RATE_LIMITED)).thenReturn(true)
        `when`(playerAnalysisService.analyze(COMMAND.gameName, COMMAND.tagLine, COMMAND.start, COMMAND.count))
            .thenThrow(RiotApiCooldownException(60))

        worker.process(COMMAND)

        verify(lifecycleService).markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.RATE_LIMITED)
        verify(inFlightRegistry).remove(requireNotNull(COMMAND.dedupeKey), COMMAND.jobId)
    }

    @Test
    fun `stores the existing deterministic unavailable status as a safe failure code`() {
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(true)
        `when`(lifecycleService.markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.UNRANKED)).thenReturn(true)
        `when`(playerAnalysisService.analyze(COMMAND.gameName, COMMAND.tagLine, COMMAND.start, COMMAND.count))
            .thenReturn(PlayerAnalysisResponse(PlayerAnalysisResponseStatus.UNRANKED, null))

        worker.process(COMMAND)

        verify(lifecycleService).markFailedIfRunning(COMMAND.jobId, AnalysisJobFailureCode.UNRANKED)
    }

    private companion object {
        val COMMAND =
            AnalysisJobCommand(
                jobId = UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf"),
                gameName = "Hide on bush",
                tagLine = "KR1",
                start = 0,
                count = 20,
                dedupeKey =
                    AnalysisJobDedupeKey.of(
                        AnalysisRateLimitKey("analysis-generation:client-a"),
                        "Hide on bush",
                        "KR1",
                        0,
                        20,
                    ),
            )
        val RESULT = PlayerAnalysisResult("summary", emptyList(), emptyList(), emptyList(), emptyList())
    }
}
