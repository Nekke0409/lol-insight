package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmarkResult
import io.github.nekke0409.lolinsight.benchmark.persistence.BenchmarkSampleAggregateRepository
import org.springframework.stereotype.Service

@Service
class PeerBenchmarkQueryService(
    private val benchmarkSampleAggregateRepository: BenchmarkSampleAggregateRepository,
    private val benchmarkAvailabilityPolicy: BenchmarkAvailabilityPolicy,
) {
    fun findBenchmark(cohort: BenchmarkCohort): PeerBenchmarkResult {
        val benchmark =
            benchmarkSampleAggregateRepository.findBenchmark(cohort)
                ?: return PeerBenchmarkResult(BenchmarkAvailability.NO_DATA, 0, 0, null)

        val status = benchmarkAvailabilityPolicy.determine(benchmark.sampleCount, benchmark.uniquePlayerCount)

        return PeerBenchmarkResult(
            status = status,
            sampleCount = benchmark.sampleCount,
            uniquePlayerCount = benchmark.uniquePlayerCount,
            benchmark = benchmark.takeIf { status == BenchmarkAvailability.AVAILABLE },
        )
    }
}
