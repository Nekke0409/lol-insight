package io.github.nekke0409.lolinsight.benchmark.domain

data class PeerBenchmark(
    val cohort: BenchmarkCohort,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
    val kda: BenchmarkMetricDistribution,
    val csPerMinute: BenchmarkMetricDistribution,
    val goldPerMinute: BenchmarkMetricDistribution,
    val damagePerMinute: BenchmarkMetricDistribution,
    val visionPerMinute: BenchmarkMetricDistribution,
    val killParticipation: BenchmarkMetricDistribution,
    val damageShare: BenchmarkMetricDistribution,
) {
    init {
        require(sampleCount > 0) { "sampleCount must be positive" }
        require(uniquePlayerCount > 0) { "uniquePlayerCount must be positive" }
        require(uniquePlayerCount <= sampleCount) { "uniquePlayerCount cannot exceed sampleCount" }
    }
}
