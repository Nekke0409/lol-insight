package io.github.nekke0409.lolinsight.automation.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TrackedPlayerAutomationJpaRepository : JpaRepository<TrackedPlayerAutomationEntity, UUID> {
    fun findByPuuid(puuid: String): TrackedPlayerAutomationEntity?

    fun findByIdAndEnabledTrue(id: UUID): TrackedPlayerAutomationEntity?

    @Query(
        """
        select automation
        from TrackedPlayerAutomationEntity automation
        where automation.enabled = true
          and (automation.lastCheckedAt is null or automation.lastCheckedAt <= :dueBefore)
        order by automation.lastCheckedAt asc nulls first, automation.createdAt asc
        """,
    )
    fun findDue(
        @Param("dueBefore") dueBefore: Instant,
        pageable: Pageable,
    ): List<TrackedPlayerAutomationEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update TrackedPlayerAutomationEntity automation
        set automation.lastSeenMatchId = :lastSeenMatchId,
            automation.lastCheckedAt = :checkedAt,
            automation.updatedAt = :updatedAt
        where automation.id = :automationId
          and automation.enabled = true
          and automation.lastCheckedAt is null
        """,
    )
    fun initializeCursorIfUninitialized(
        @Param("automationId") automationId: UUID,
        @Param("lastSeenMatchId") lastSeenMatchId: String?,
        @Param("checkedAt") checkedAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update TrackedPlayerAutomationEntity automation
        set automation.lastCheckedAt = :checkedAt,
            automation.updatedAt = :updatedAt
        where automation.id = :automationId
          and automation.enabled = true
          and ((:expectedLastSeenMatchId is null and automation.lastSeenMatchId is null)
              or automation.lastSeenMatchId = :expectedLastSeenMatchId)
        """,
    )
    fun markCheckedIfCursorUnchanged(
        @Param("automationId") automationId: UUID,
        @Param("expectedLastSeenMatchId") expectedLastSeenMatchId: String?,
        @Param("checkedAt") checkedAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update TrackedPlayerAutomationEntity automation
        set automation.lastSeenMatchId = :newLastSeenMatchId,
            automation.lastCheckedAt = :checkedAt,
            automation.updatedAt = :updatedAt
        where automation.id = :automationId
          and automation.enabled = true
          and ((:expectedLastSeenMatchId is null and automation.lastSeenMatchId is null)
              or automation.lastSeenMatchId = :expectedLastSeenMatchId)
        """,
    )
    fun advanceCursorIfUnchanged(
        @Param("automationId") automationId: UUID,
        @Param("expectedLastSeenMatchId") expectedLastSeenMatchId: String?,
        @Param("newLastSeenMatchId") newLastSeenMatchId: String,
        @Param("checkedAt") checkedAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
