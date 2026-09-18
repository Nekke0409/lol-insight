package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import org.springframework.stereotype.Component

@Component
class BenchmarkReplenishmentPlanner(
    private val benchmarkAvailabilityPolicy: BenchmarkAvailabilityPolicy,
) {
    fun plan(
        cohort: BenchmarkReplenishmentCohort,
        window: BenchmarkQueryWindow,
        coverage: List<BenchmarkCohortCoverage>,
    ): BenchmarkReplenishmentPlan {
        val scope = cohort.coverageScope()
        val positionRows = coverage.filter { it.cohort.scope == BenchmarkScope.POSITION }
        val coverageBelongsToCohort =
            positionRows.all { row ->
                row.cohort.region == scope.region &&
                    row.cohort.queueId == scope.queueId &&
                    row.cohort.tier == scope.tier &&
                    row.cohort.division == scope.division
            }
        require(coverageBelongsToCohort) { "coverage must belong to the planned cohort" }
        require(positionRows.groupBy { it.cohort.position }.all { it.value.size == 1 }) {
            "coverage cannot contain duplicate POSITION rows"
        }

        val coverageByPosition = positionRows.associateBy { it.cohort.position }
        val allPositionCoverage =
            POSITIONS.map { position ->
                coverageByPosition[position] ?: noDataCoverage(cohort, position)
            }

        return BenchmarkReplenishmentPlan(
            cohort = cohort,
            window = window,
            positionCoverage = allPositionCoverage,
            status =
                if (allPositionCoverage.all { it.availability == BenchmarkAvailability.AVAILABLE }) {
                    BenchmarkReplenishmentStatus.HEALTHY
                } else {
                    BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT
                },
        )
    }

    fun prioritize(plans: List<BenchmarkReplenishmentPlan>): List<BenchmarkReplenishmentPlan> =
        plans
            .filter { it.status == BenchmarkReplenishmentStatus.NEEDS_REPLENISHMENT }
            .sortedWith(
                compareByDescending<BenchmarkReplenishmentPlan> { plan ->
                    plan.insufficientPositions.count { it.availability == BenchmarkAvailability.NO_DATA }
                }.thenByDescending { it.insufficientPositions.size }
                    .thenByDescending { plan ->
                        plan.insufficientPositions.sumOf { it.samplesNeeded + it.uniquePlayersNeeded }
                    }.thenBy { it.cohort.tier }
                    .thenBy { it.cohort.division },
            )

    private fun noDataCoverage(
        cohort: BenchmarkReplenishmentCohort,
        position: String,
    ): BenchmarkCohortCoverage =
        BenchmarkCohortCoverage(
            cohort =
                BenchmarkCohort.position(
                    region = BenchmarkReplenishmentCohort.KR_REGION,
                    queueId = cohort.coverageScope().queueId,
                    tier = cohort.tier,
                    division = cohort.division,
                    position = position,
                ),
            sampleCount = 0,
            uniquePlayerCount = 0,
            availability = benchmarkAvailabilityPolicy.determine(0, 0),
            samplesNeeded = benchmarkAvailabilityPolicy.samplesNeeded(0),
            uniquePlayersNeeded = benchmarkAvailabilityPolicy.uniquePlayersNeeded(0),
        )

    companion object {
        val POSITIONS = listOf("TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY")
    }
}

data class BenchmarkReplenishmentPlan(
    val cohort: BenchmarkReplenishmentCohort,
    val window: BenchmarkQueryWindow,
    val positionCoverage: List<BenchmarkCohortCoverage>,
    val status: BenchmarkReplenishmentStatus,
) {
    val insufficientPositions: List<BenchmarkCohortCoverage>
        get() = positionCoverage.filter { it.availability != BenchmarkAvailability.AVAILABLE }
}

enum class BenchmarkReplenishmentStatus {
    HEALTHY,
    NEEDS_REPLENISHMENT,
}
