package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmark
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmarkResult
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import org.springframework.stereotype.Service

@Service
class PeerBenchmarkQueryService(
    private val benchmarkSampleAggregateRepository: BenchmarkSampleAggregateRepository,
    private val benchmarkAvailabilityPolicy: BenchmarkAvailabilityPolicy,
) {
    fun findBenchmark(cohort: BenchmarkCohort): PeerBenchmarkResult = toResult(benchmarkSampleAggregateRepository.findBenchmark(cohort))

    fun findBenchmarkExcludingPlayer(
        cohort: BenchmarkCohort,
        excludedPuuid: String,
    ): PeerBenchmarkResult {
        require(excludedPuuid.isNotBlank()) { "excludedPuuid must not be blank" }

        return toResult(benchmarkSampleAggregateRepository.findBenchmarkExcludingPlayer(cohort, excludedPuuid))
    }

    private fun toResult(benchmark: PeerBenchmark?): PeerBenchmarkResult {
        benchmark ?: return PeerBenchmarkResult(BenchmarkAvailability.NO_DATA, 0, 0, null)
        val status = benchmarkAvailabilityPolicy.determine(benchmark.sampleCount, benchmark.uniquePlayerCount)

        return PeerBenchmarkResult(
            status = status,
            sampleCount = benchmark.sampleCount,
            uniquePlayerCount = benchmark.uniquePlayerCount,
            benchmark = benchmark.takeIf { status == BenchmarkAvailability.AVAILABLE },
        )
    }
}
