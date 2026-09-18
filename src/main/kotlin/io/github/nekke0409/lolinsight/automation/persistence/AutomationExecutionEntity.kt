package io.github.nekke0409.lolinsight.automation.persistence

import io.github.nekke0409.lolinsight.automation.domain.AutomationExecution
import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "automation_execution")
class AutomationExecutionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "automation_id", nullable = false)
    var automationId: UUID = UUID.randomUUID(),
    @Column(name = "detected_match_id", nullable = false, length = 100)
    var detectedMatchId: String = "",
    @Column(name = "analysis_job_id")
    var analysisJobId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AutomationExecutionStatus = AutomationExecutionStatus.CLAIMED,
    @Column(name = "detected_at", nullable = false)
    var detectedAt: Instant = Instant.EPOCH,
    @Column(name = "triggered_at")
    var triggeredAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    fun toDomain(): AutomationExecution =
        AutomationExecution(
            id = id,
            automationId = automationId,
            detectedMatchId = detectedMatchId,
            analysisJobId = analysisJobId,
            status = status,
            detectedAt = detectedAt,
            triggeredAt = triggeredAt,
        )
}
