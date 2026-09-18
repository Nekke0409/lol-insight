package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobService
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.observability.AutomationObservationRecorder
import io.github.nekke0409.lolinsight.automation.scheduling.AnalysisAutomationProperties
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.application.PlayerService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class NewRankedMatchAnalysisPollingService(
    private val trackedPlayerAutomationService: TrackedPlayerAutomationService,
    private val automationExecutionService: AutomationExecutionService,
    private val riotMatchClient: RiotMatchClient,
    private val playerService: PlayerService,
    private val analysisJobService: AnalysisJobService,
    private val properties: AnalysisAutomationProperties,
    private val clock: Clock,
    private val observationRecorder: AutomationObservationRecorder,
) {
    /** Runs one bounded, sequential scheduler tick. Failures leave the affected cursor unchanged. */
    fun pollDue() {
        val now = Instant.now(clock)
        val dueAutomations =
            trackedPlayerAutomationService.findDue(
                dueBefore = now.minus(properties.pollInterval),
                batchSize = properties.batchSize,
            )

        dueAutomations.forEach { automation ->
            try {
                poll(automation.id)
            } catch (_: Exception) {
                observationRecorder.recordPollFailure()
            }
        }
    }

    /**
     * Polls a single persisted automation. This is intentionally callable without time-based
     * scheduling so its deterministic rules can be tested directly.
     */
    fun poll(automationId: UUID): AutomationPollOutcome {
        val automation = trackedPlayerAutomationService.findEnabled(automationId) ?: return AutomationPollOutcome.DISABLED
        val matchIds =
            riotMatchClient.findMatchIdsByPuuid(
                puuid = automation.puuid,
                start = ANALYSIS_START,
                count = ANALYSIS_COUNT,
                queue = RankedSoloQueue.ID,
            )
        val checkedAt = Instant.now(clock)
        observationRecorder.recordPollSuccess()

        if (automation.lastCheckedAt == null) {
            trackedPlayerAutomationService.initializeCursorIfUninitialized(
                automationId = automation.id,
                lastSeenMatchId = matchIds.firstOrNull(),
                checkedAt = checkedAt,
            )
            return AutomationPollOutcome.INITIALIZED
        }

        val latestMatchId = matchIds.firstOrNull()
        if (latestMatchId == null || latestMatchId == automation.lastSeenMatchId) {
            trackedPlayerAutomationService.markCheckedIfCursorUnchanged(automation, checkedAt)
            return AutomationPollOutcome.NO_NEW_MATCH
        }

        observationRecorder.recordNewMatchDetected()
        return when (val claim = automationExecutionService.claim(automation.id, latestMatchId, checkedAt)) {
            is AutomationExecutionClaim.Existing -> resumeOrSkipExisting(automation, claim.execution, checkedAt)
            is AutomationExecutionClaim.Created -> createAnalysisJob(automation, claim.execution.id, latestMatchId, checkedAt)
        }
    }

    private fun resumeOrSkipExisting(
        automation: io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation,
        execution: io.github.nekke0409.lolinsight.automation.domain.AutomationExecution,
        checkedAt: Instant,
    ): AutomationPollOutcome {
        if (execution.status == AutomationExecutionStatus.JOB_CREATED &&
            trackedPlayerAutomationService.advanceCursorIfUnchanged(automation, execution.detectedMatchId, checkedAt)
        ) {
            automationExecutionService.markTriggered(execution.id, checkedAt)
        }
        observationRecorder.recordTriggerSkipped()
        return AutomationPollOutcome.SKIPPED_DUPLICATE
    }

    private fun createAnalysisJob(
        automation: io.github.nekke0409.lolinsight.automation.domain.TrackedPlayerAutomation,
        executionId: UUID,
        detectedMatchId: String,
        checkedAt: Instant,
    ): AutomationPollOutcome {
        var jobAccepted = false
        try {
            val player = playerService.findByPuuid(automation.puuid)
            val job =
                analysisJobService.createFromAutomation(
                    gameName = player.gameName,
                    tagLine = player.tagLine,
                    start = ANALYSIS_START,
                    count = ANALYSIS_COUNT,
                )
            jobAccepted = true
            check(automationExecutionService.markJobCreated(executionId, job.jobId, checkedAt)) {
                "Automation execution must be claimed before a job is attached"
            }

            if (trackedPlayerAutomationService.advanceCursorIfUnchanged(automation, detectedMatchId, checkedAt)) {
                check(automationExecutionService.markTriggered(executionId, checkedAt)) {
                    "Automation execution must have a created job before it is triggered"
                }
            }
            observationRecorder.recordTriggerTriggered()
            return AutomationPollOutcome.TRIGGERED
        } catch (exception: Exception) {
            if (!jobAccepted) {
                automationExecutionService.discardClaim(executionId)
            }
            observationRecorder.recordTriggerFailure()
            throw exception
        }
    }

    private companion object {
        const val ANALYSIS_START = 0
        const val ANALYSIS_COUNT = 20
    }
}

enum class AutomationPollOutcome {
    DISABLED,
    INITIALIZED,
    NO_NEW_MATCH,
    TRIGGERED,
    SKIPPED_DUPLICATE,
}
