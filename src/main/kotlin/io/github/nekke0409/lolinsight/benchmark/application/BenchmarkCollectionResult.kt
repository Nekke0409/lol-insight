package io.github.nekke0409.lolinsight.benchmark.application

data class BenchmarkCollectionResult(
    val inputPlayers: Int,
    val playersProcessed: Int,
    val playerMatchListFailures: Int,
    val discoveredMatchIds: Int,
    val uniqueMatchIds: Int,
    val fetchedMatches: Int,
    val failedMatches: Int,
    val createdSamples: Int,
    val skippedDuplicates: Int,
    val skippedInvalidSamples: Int,
    val rateLimitStopped: Boolean,
    val retryAfterSeconds: Long?,
)
