package io.github.nekke0409.lolinsight.benchmark.domain

data class BenchmarkCohortCoverage(
    val cohort: BenchmarkCohort,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
    val availability: BenchmarkAvailability,
    val samplesNeeded: Long,
    val uniquePlayersNeeded: Long,
) {
    init {
        require(sampleCount > 0) { "sampleCount must be positive" }
        require(uniquePlayerCount > 0) { "uniquePlayerCount must be positive" }
        require(uniquePlayerCount <= sampleCount) { "uniquePlayerCount cannot exceed sampleCount" }
        require(samplesNeeded >= 0) { "samplesNeeded cannot be negative" }
        require(uniquePlayersNeeded >= 0) { "uniquePlayersNeeded cannot be negative" }
    }
}
