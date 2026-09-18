package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkReplenishmentCursorJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class BenchmarkReplenishmentCursorService(
    private val repository: BenchmarkReplenishmentCursorJpaRepository,
    private val clock: Clock,
) {
    @Transactional
    fun getOrCreate(cohort: BenchmarkReplenishmentCohort): BenchmarkReplenishmentCursor {
        val scope = cohort.coverageScope()
        val now = clock.instant()
        repository.insertIfAbsent(scope.region, scope.queueId, scope.tier, scope.division, now)
        val entity =
            checkNotNull(
                repository.findByRegionAndQueueIdAndTierAndDivision(
                    scope.region,
                    scope.queueId,
                    scope.tier,
                    scope.division,
                ),
            ) { "benchmark replenishment cursor was not created" }

        return BenchmarkReplenishmentCursor(
            cohort = cohort,
            nextPage = entity.nextPage,
            lastAttemptedAt = entity.lastAttemptedAt,
        )
    }

    @Transactional
    fun advance(
        cursor: BenchmarkReplenishmentCursor,
        nextPage: Int,
    ) {
        require(nextPage > 0) { "nextPage must be positive" }
        val scope = cursor.cohort.coverageScope()
        val entity =
            checkNotNull(
                repository.findByRegionAndQueueIdAndTierAndDivision(
                    scope.region,
                    scope.queueId,
                    scope.tier,
                    scope.division,
                ),
            ) { "benchmark replenishment cursor does not exist" }
        val now = clock.instant()
        check(repository.advance(checkNotNull(entity.id), nextPage, now, now) == 1) {
            "benchmark replenishment cursor was not advanced"
        }
    }
}

data class BenchmarkReplenishmentCursor(
    val cohort: BenchmarkReplenishmentCohort,
    val nextPage: Int,
    val lastAttemptedAt: Instant?,
) {
    init {
        require(nextPage > 0) { "nextPage must be positive" }
    }
}
