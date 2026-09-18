package io.github.nekke0409.lolinsight.benchmark.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface BenchmarkReplenishmentCursorJpaRepository : JpaRepository<BenchmarkReplenishmentCursorEntity, Long> {
    fun findByRegionAndQueueIdAndTierAndDivision(
        region: String,
        queueId: Int,
        tier: String,
        division: String,
    ): BenchmarkReplenishmentCursorEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            """
            INSERT INTO benchmark_replenishment_cursor (
                region, queue_id, tier, division, next_page, updated_at
            ) VALUES (
                :region, :queueId, :tier, :division, 1, :updatedAt
            ) ON CONFLICT ON CONSTRAINT uq_benchmark_replenishment_cursor_cohort DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("region") region: String,
        @Param("queueId") queueId: Int,
        @Param("tier") tier: String,
        @Param("division") division: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update BenchmarkReplenishmentCursorEntity cursor
        set cursor.nextPage = :nextPage,
            cursor.lastAttemptedAt = :attemptedAt,
            cursor.updatedAt = :updatedAt
        where cursor.id = :cursorId
        """,
    )
    fun advance(
        @Param("cursorId") cursorId: Long,
        @Param("nextPage") nextPage: Int,
        @Param("attemptedAt") attemptedAt: Instant,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
