package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCapacityExceededException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCreated
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecution
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation
import io.github.nekke0409.lolinsight.automation.observability.AutomationObservationRecorder
import io.github.nekke0409.lolinsight.automation.scheduling.AnalysisAutomationProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldownException
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NewRankedMatchAnalysisPollingServiceTest {
    private val trackedPlayerAutomationService = mock(TrackedPlayerAutomationService::class.java)
    private val automationExecutionService = mock(AutomationExecutionService::class.java)
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val playerService = mock(PlayerService::class.java)
    private val analysisJobService = mock(AnalysisJobService::class.java)
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val riotApiCooldown = RiotApiCooldown(RiotApiProperties(key = "test-api-key"), Clock.fixed(now, ZoneOffset.UTC))
    private val service =
        NewRankedMatchAnalysisPollingService(
            trackedPlayerAutomationService,
            automationExecutionService,
            riotMatchClient,
            playerService,
            analysisJobService,
            AnalysisAutomationProperties(pollInterval = Duration.ofMinutes(5), batchSize = 10),
            Clock.fixed(now, ZoneOffset.UTC),
            AutomationObservationRecorder(SimpleMeterRegistry()),
            riotApiCooldown,
        )

    @Test
    fun `baselines an uninitialized automation without triggering historical matches`() {
        val uninitialized = AUTOMATION.copy(lastSeenMatchId = null, lastCheckedAt = null)
        `when`(trackedPlayerAutomationService.findEnabled(AUTOMATION_ID)).thenReturn(uninitialized)
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_100", "KR_99"))

        assertEquals(AutomationPollOutcome.INITIALIZED, service.poll(AUTOMATION_ID))

        verify(trackedPlayerAutomationService).initializeCursorIfUninitialized(AUTOMATION_ID, "KR_100", now)
        verifyNoInteractions(automationExecutionService, playerService, analysisJobService)
    }

    @Test
    fun `does not create a job when the latest match equals the cursor`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_100", "KR_99"))

        assertEquals(AutomationPollOutcome.NO_NEW_MATCH, service.poll(AUTOMATION_ID))

        verify(trackedPlayerAutomationService).markCheckedIfCursorUnchanged(AUTOMATION, now)
        verifyNoInteractions(automationExecutionService, playerService, analysisJobService)
    }

    @Test
    fun `creates one rolling analysis job for one new Ranked Solo match`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_101", "KR_100"))
        givenNewExecution("KR_101")

        assertEquals(AutomationPollOutcome.TRIGGERED, service.poll(AUTOMATION_ID))

        verify(analysisJobService).createFromAutomation(GAME_NAME, TAG_LINE, 0, 20)
        verify(trackedPlayerAutomationService).advanceCursorIfUnchanged(AUTOMATION, "KR_101", now)
        verify(automationExecutionService).markTriggered(EXECUTION_ID, now)
    }

    @Test
    fun `coalesces multiple new matches into one rolling analysis job`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID))
            .thenReturn(listOf("KR_103", "KR_102", "KR_101", "KR_100"))
        givenNewExecution("KR_103")

        assertEquals(AutomationPollOutcome.TRIGGERED, service.poll(AUTOMATION_ID))

        verify(automationExecutionService).claim(AUTOMATION_ID, "KR_103", now)
        verify(analysisJobService).createFromAutomation(GAME_NAME, TAG_LINE, 0, 20)
        verify(analysisJobService, never()).createFromAutomation(GAME_NAME, TAG_LINE, 1, 1)
    }

    @Test
    fun `skips an already recorded match without a duplicate AnalysisJob`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_101", "KR_100"))
        `when`(automationExecutionService.claim(AUTOMATION_ID, "KR_101", now))
            .thenReturn(AutomationExecutionClaim.Existing(EXECUTION.copy(status = AutomationExecutionStatus.TRIGGERED)))

        assertEquals(AutomationPollOutcome.SKIPPED_DUPLICATE, service.poll(AUTOMATION_ID))

        verifyNoInteractions(playerService, analysisJobService)
    }

    @Test
    fun `resumes cursor advancement for a job already created before a previous cursor update failed`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_101", "KR_100"))
        `when`(automationExecutionService.claim(AUTOMATION_ID, "KR_101", now))
            .thenReturn(
                AutomationExecutionClaim.Existing(
                    EXECUTION.copy(
                        detectedMatchId = "KR_101",
                        analysisJobId = JOB.jobId,
                        status = AutomationExecutionStatus.JOB_CREATED,
                    ),
                ),
            )
        `when`(trackedPlayerAutomationService.advanceCursorIfUnchanged(AUTOMATION, "KR_101", now)).thenReturn(true)
        `when`(automationExecutionService.markTriggered(EXECUTION_ID, now)).thenReturn(true)

        assertEquals(AutomationPollOutcome.SKIPPED_DUPLICATE, service.poll(AUTOMATION_ID))

        verify(trackedPlayerAutomationService).advanceCursorIfUnchanged(AUTOMATION, "KR_101", now)
        verify(automationExecutionService).markTriggered(EXECUTION_ID, now)
        verifyNoInteractions(playerService, analysisJobService)
    }

    @Test
    fun `does not advance the cursor when job creation is rejected for capacity`() {
        givenAutomation()
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenReturn(listOf("KR_101", "KR_100"))
        `when`(automationExecutionService.claim(AUTOMATION_ID, "KR_101", now))
            .thenReturn(AutomationExecutionClaim.Created(EXECUTION))
        `when`(playerService.findByPuuid(PUUID)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
        `when`(analysisJobService.createFromAutomation(GAME_NAME, TAG_LINE, 0, 20))
            .thenThrow(AnalysisJobCapacityExceededException())

        assertFailsWith<AnalysisJobCapacityExceededException> { service.poll(AUTOMATION_ID) }

        verify(automationExecutionService).discardClaim(EXECUTION_ID)
        verify(trackedPlayerAutomationService, never()).advanceCursorIfUnchanged(AUTOMATION, "KR_101", now)
    }

    @Test
    fun `leaves cursor and trigger untouched when Riot polling fails`() {
        givenAutomation()
        val exception = RiotApiTransportException(IllegalStateException("timeout"))
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenThrow(exception)

        assertEquals(exception, assertFailsWith<RiotApiTransportException> { service.poll(AUTOMATION_ID) })

        verifyNoInteractions(automationExecutionService, playerService, analysisJobService)
        verify(trackedPlayerAutomationService, never()).markCheckedIfCursorUnchanged(AUTOMATION, now)
    }

    @Test
    fun `stops the current tick after a Riot 429 without polling later automations`() {
        val laterAutomation = AUTOMATION.copy(id = UUID.fromString("ebba5c25-c974-4465-86b5-7e4b6449dd82"), puuid = "later-puuid")
        `when`(trackedPlayerAutomationService.findDue(now.minus(Duration.ofMinutes(5)), 10))
            .thenReturn(listOf(AUTOMATION, laterAutomation))
        `when`(trackedPlayerAutomationService.findEnabled(AUTOMATION_ID)).thenReturn(AUTOMATION)
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID))
            .thenThrow(RiotApiResponseException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", retryAfterSeconds = 10))

        service.pollDue()

        verify(riotMatchClient).findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)
        verify(trackedPlayerAutomationService, never()).findEnabled(laterAutomation.id)
        verifyNoInteractions(automationExecutionService, playerService, analysisJobService)
        verify(trackedPlayerAutomationService, never()).markCheckedIfCursorUnchanged(AUTOMATION, now)
    }

    @Test
    fun `skips an entire tick while the shared Riot cooldown is active`() {
        riotApiCooldown.registerRateLimit(10 * 60)

        service.pollDue()

        verifyNoInteractions(
            trackedPlayerAutomationService,
            riotMatchClient,
            automationExecutionService,
            playerService,
            analysisJobService,
        )
    }

    @Test
    fun `stops the current tick when the common HTTP boundary reports local cooldown`() {
        val laterAutomation = AUTOMATION.copy(id = UUID.fromString("ebba5c25-c974-4465-86b5-7e4b6449dd82"), puuid = "later-puuid")
        `when`(trackedPlayerAutomationService.findDue(now.minus(Duration.ofMinutes(5)), 10))
            .thenReturn(listOf(AUTOMATION, laterAutomation))
        `when`(trackedPlayerAutomationService.findEnabled(AUTOMATION_ID)).thenReturn(AUTOMATION)
        `when`(riotMatchClient.findMatchIdsByPuuid(PUUID, 0, 20, RankedSoloQueue.ID)).thenThrow(RiotApiCooldownException(10))

        service.pollDue()

        verify(trackedPlayerAutomationService, never()).findEnabled(laterAutomation.id)
        verifyNoInteractions(automationExecutionService, playerService, analysisJobService)
    }

    @Test
    fun `does not poll a disabled automation`() {
        `when`(trackedPlayerAutomationService.findEnabled(AUTOMATION_ID)).thenReturn(null)

        assertEquals(AutomationPollOutcome.DISABLED, service.poll(AUTOMATION_ID))

        verifyNoInteractions(riotMatchClient, automationExecutionService, playerService, analysisJobService)
    }

    private fun givenAutomation() {
        `when`(trackedPlayerAutomationService.findEnabled(AUTOMATION_ID)).thenReturn(AUTOMATION)
    }

    private fun givenNewExecution(detectedMatchId: String) {
        `when`(automationExecutionService.claim(AUTOMATION_ID, detectedMatchId, now))
            .thenReturn(AutomationExecutionClaim.Created(EXECUTION.copy(detectedMatchId = detectedMatchId)))
        `when`(playerService.findByPuuid(PUUID)).thenReturn(PlayerResponse(PUUID, GAME_NAME, TAG_LINE))
        `when`(analysisJobService.createFromAutomation(GAME_NAME, TAG_LINE, 0, 20)).thenReturn(JOB)
        `when`(automationExecutionService.markJobCreated(EXECUTION_ID, JOB.jobId, now)).thenReturn(true)
        `when`(trackedPlayerAutomationService.advanceCursorIfUnchanged(AUTOMATION, detectedMatchId, now)).thenReturn(true)
        `when`(automationExecutionService.markTriggered(EXECUTION_ID, now)).thenReturn(true)
    }

    private companion object {
        val AUTOMATION_ID = UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf")
        val EXECUTION_ID = UUID.fromString("1c565b91-2ac1-4e7a-b2d1-7adc3c766938")
        const val PUUID = "stable-puuid"
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        val AUTOMATION =
            TrackedPlayerAutomation(
                AUTOMATION_ID,
                PUUID,
                true,
                "KR_100",
                Instant.parse("2026-09-18T11:55:00Z"),
                Instant.parse("2026-09-18T11:00:00Z"),
                Instant.parse("2026-09-18T11:55:00Z"),
            )
        val EXECUTION =
            AutomationExecution(
                EXECUTION_ID,
                AUTOMATION_ID,
                "KR_101",
                null,
                AutomationExecutionStatus.CLAIMED,
                Instant.parse("2026-09-18T12:00:00Z"),
                null,
            )
        val JOB =
            AnalysisJobCreated(
                UUID.fromString("1fe84b4f-d3cf-4451-8aae-bc8f87cf60c8"),
                AnalysisJobStatus.PENDING,
                Instant.parse("2026-09-18T12:00:00Z"),
            )
    }
}
