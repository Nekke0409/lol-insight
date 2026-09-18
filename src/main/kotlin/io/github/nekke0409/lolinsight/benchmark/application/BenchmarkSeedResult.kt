package io.github.nekke0409.lolinsight.benchmark.application

data class BenchmarkSeedResult(
    val requestedStartPage: Int,
    val requestedPageCount: Int,
    val discoveredPlayers: Int,
    val uniquePlayers: Int,
    val pagesProcessed: Int,
    val collectionResult: BenchmarkCollectionResult?,
    val rateLimitStopped: Boolean,
    val retryAfterSeconds: Long?,
    val emptyPageEncountered: Boolean = false,
) {
    init {
        require(requestedStartPage > 0) { "requestedStartPage must be positive" }
        require(requestedPageCount > 0) { "requestedPageCount must be positive" }
        require(discoveredPlayers >= uniquePlayers) { "discoveredPlayers cannot be less than uniquePlayers" }
        require(uniquePlayers >= 0) { "uniquePlayers cannot be negative" }
        require(pagesProcessed >= 0) { "pagesProcessed cannot be negative" }
    }

    val createdSamples: Int
        get() = collectionResult?.createdSamples ?: 0

    val skippedDuplicates: Int
        get() = collectionResult?.skippedDuplicates ?: 0

    val skippedInvalidSamples: Int
        get() = collectionResult?.skippedInvalidSamples ?: 0

    val playerMatchListFailures: Int
        get() = collectionResult?.playerMatchListFailures ?: 0

    val failedMatches: Int
        get() = collectionResult?.failedMatches ?: 0
}
