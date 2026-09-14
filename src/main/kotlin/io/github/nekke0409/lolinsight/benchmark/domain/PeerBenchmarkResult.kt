package io.github.nekke0409.lolinsight.benchmark.domain

data class PeerBenchmarkResult(
    val status: BenchmarkAvailability,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
    val benchmark: PeerBenchmark?,
) {
    init {
        require(sampleCount >= 0) { "sampleCount cannot be negative" }
        require(uniquePlayerCount >= 0) { "uniquePlayerCount cannot be negative" }
        require(uniquePlayerCount <= sampleCount) { "uniquePlayerCount cannot exceed sampleCount" }

        when (status) {
            BenchmarkAvailability.NO_DATA -> {
                require(sampleCount == 0L) { "NO_DATA must not have samples" }
                require(uniquePlayerCount == 0L) { "NO_DATA must not have unique players" }
                require(benchmark == null) { "NO_DATA must not include a benchmark" }
            }

            BenchmarkAvailability.INSUFFICIENT_SAMPLE -> {
                require(sampleCount > 0) { "INSUFFICIENT_SAMPLE must have samples" }
                require(benchmark == null) { "INSUFFICIENT_SAMPLE must not include a benchmark" }
            }

            BenchmarkAvailability.AVAILABLE -> {
                require(benchmark != null) { "AVAILABLE must include a benchmark" }
                require(benchmark.sampleCount == sampleCount) { "Benchmark sampleCount must match result" }
                require(benchmark.uniquePlayerCount == uniquePlayerCount) { "Benchmark uniquePlayerCount must match result" }
            }
        }
    }
}

enum class BenchmarkAvailability {
    NO_DATA,
    INSUFFICIENT_SAMPLE,
    AVAILABLE,
}
