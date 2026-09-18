package io.github.nekke0409.lolinsight.automation.application

import io.github.nekke0409.lolinsight.automation.domain.AutomationExecution
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import io.github.nekke0409.lolinsight.automation.persistence.AutomationExecutionJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AutomationExecutionService(
    private val automationExecutionJpaRepository: AutomationExecutionJpaRepository,
) {
    @Transactional
    fun claim(
        automationId: UUID,
        detectedMatchId: String,
        detectedAt: Instant,
    ): AutomationExecutionClaim {
        val executionId = UUID.randomUUID()
        val inserted =
            automationExecutionJpaRepository.insertIfAbsent(
                id = executionId,
                automationId = automationId,
                detectedMatchId = detectedMatchId,
                status = AutomationExecutionStatus.CLAIMED.name,
                detectedAt = detectedAt,
                updatedAt = detectedAt,
            ) == 1
        val execution =
            automationExecutionJpaRepository
                .findByAutomationIdAndDetectedMatchId(automationId, detectedMatchId)
                ?.toDomain()
                ?: error("Automation execution claim was not persisted")

        return if (inserted) {
            AutomationExecutionClaim.Created(execution)
        } else {
            AutomationExecutionClaim.Existing(execution)
        }
    }

    @Transactional
    fun markJobCreated(
        executionId: UUID,
        analysisJobId: UUID,
        updatedAt: Instant,
    ): Boolean =
        automationExecutionJpaRepository.markJobCreated(
            executionId = executionId,
            analysisJobId = analysisJobId,
            claimedStatus = AutomationExecutionStatus.CLAIMED,
            jobCreatedStatus = AutomationExecutionStatus.JOB_CREATED,
            updatedAt = updatedAt,
        ) == 1

    @Transactional
    fun markTriggered(
        executionId: UUID,
        triggeredAt: Instant,
    ): Boolean =
        automationExecutionJpaRepository.markTriggered(
            executionId = executionId,
            jobCreatedStatus = AutomationExecutionStatus.JOB_CREATED,
            triggeredStatus = AutomationExecutionStatus.TRIGGERED,
            triggeredAt = triggeredAt,
            updatedAt = triggeredAt,
        ) == 1

    @Transactional
    fun discardClaim(executionId: UUID) {
        automationExecutionJpaRepository.deleteClaimed(executionId, AutomationExecutionStatus.CLAIMED)
    }
}

sealed interface AutomationExecutionClaim {
    data class Created(
        val execution: AutomationExecution,
    ) : AutomationExecutionClaim

    data class Existing(
        val execution: AutomationExecution,
    ) : AutomationExecutionClaim
}
