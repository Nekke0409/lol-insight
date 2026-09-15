package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkCohortCoverageRepository
import org.springframework.stereotype.Service

@Service
class BenchmarkCohortCoverageQueryService(
    private val benchmarkCohortCoverageRepository: BenchmarkCohortCoverageRepository,
    private val benchmarkAvailabilityPolicy: BenchmarkAvailabilityPolicy,
) {
    fun findCoverage(scope: BenchmarkCohortCoverageScope): List<BenchmarkCohortCoverage> =
        benchmarkCohortCoverageRepository
            .findCoverage(scope)
            .map { row ->
                BenchmarkCohortCoverage(
                    cohort = row.cohort,
                    sampleCount = row.sampleCount,
                    uniquePlayerCount = row.uniquePlayerCount,
                    availability = benchmarkAvailabilityPolicy.determine(row.sampleCount, row.uniquePlayerCount),
                    samplesNeeded = benchmarkAvailabilityPolicy.samplesNeeded(row.sampleCount),
                    uniquePlayersNeeded = benchmarkAvailabilityPolicy.uniquePlayersNeeded(row.uniquePlayerCount),
                )
            }.sortedWith(
                compareBy<BenchmarkCohortCoverage> { it.availability != BenchmarkAvailability.AVAILABLE }
                    .thenByDescending(BenchmarkCohortCoverage::uniquePlayerCount)
                    .thenByDescending(BenchmarkCohortCoverage::sampleCount)
                    .thenBy { it.cohort.scope }
                    .thenBy { it.cohort.position }
                    .thenBy { it.cohort.championId ?: 0 },
            )
}
