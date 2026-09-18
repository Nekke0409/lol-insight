package io.github.nekke0409.lolinsight.automation.domain

import java.time.Instant
import java.util.UUID

data class AutomationExecution(
    val id: UUID,
    val automationId: UUID,
    val detectedMatchId: String,
    val analysisJobId: UUID?,
    val status: AutomationExecutionStatus,
    val detectedAt: Instant,
    val triggeredAt: Instant?,
)

enum class AutomationExecutionStatus {
    CLAIMED,
    JOB_CREATED,
    TRIGGERED,
}
