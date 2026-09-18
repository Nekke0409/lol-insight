package io.github.nekke0409.lolinsight.automation.persistence

import io.github.nekke0409.lolinsight.automation.domain.AutomationExecutionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AutomationExecutionJpaRepository : JpaRepository<AutomationExecutionEntity, UUID> {
    fun findByAutomationIdAndDetectedMatchId(
        automationId: UUID,
        detectedMatchId: String,
    ): AutomationExecutionEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            """
            insert into automation_execution (
                id, automation_id, detected_match_id, status, detected_at, updated_at
            ) values (
                :id, :automationId, :detectedMatchId, :status, :detectedAt, :updatedAt
            ) on conflict (automation_id, detected_match_id) do nothing
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("automationId") automationId: UUID,
        @Param("detectedMatchId") detectedMatchId: String,
        @Param("status") status: String,
        @Param("detectedAt") detectedAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AutomationExecutionEntity execution
        set execution.analysisJobId = :analysisJobId,
            execution.status = :jobCreatedStatus,
            execution.updatedAt = :updatedAt
        where execution.id = :executionId
          and execution.status = :claimedStatus
        """,
    )
    fun markJobCreated(
        @Param("executionId") executionId: UUID,
        @Param("analysisJobId") analysisJobId: UUID,
        @Param("claimedStatus") claimedStatus: AutomationExecutionStatus,
        @Param("jobCreatedStatus") jobCreatedStatus: AutomationExecutionStatus,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update AutomationExecutionEntity execution
        set execution.status = :triggeredStatus,
            execution.triggeredAt = :triggeredAt,
            execution.updatedAt = :updatedAt
        where execution.id = :executionId
          and execution.status = :jobCreatedStatus
        """,
    )
    fun markTriggered(
        @Param("executionId") executionId: UUID,
        @Param("jobCreatedStatus") jobCreatedStatus: AutomationExecutionStatus,
        @Param("triggeredStatus") triggeredStatus: AutomationExecutionStatus,
        @Param("triggeredAt") triggeredAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        delete from AutomationExecutionEntity execution
        where execution.id = :executionId
          and execution.status = :claimedStatus
        """,
    )
    fun deleteClaimed(
        @Param("executionId") executionId: UUID,
        @Param("claimedStatus") claimedStatus: AutomationExecutionStatus,
    ): Int
}
