package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow

data class BenchmarkSeedRequest(
    val tier: String,
    val division: String,
    val startPage: Int,
    val pageCount: Int,
    val playerLimit: Int,
    val matchesPerPlayer: Int,
    val queryWindow: BenchmarkQueryWindow? = null,
) {
    init {
        require(tier in SUPPORTED_TIERS) { "tier must be a supported League-V4 tier" }
        require(division in SUPPORTED_DIVISIONS) { "division must be a supported League-V4 division" }
        require(startPage > 0) { "startPage must be positive" }
        require(pageCount > 0) { "pageCount must be positive" }
        require(startPage <= Int.MAX_VALUE - pageCount + 1) { "requested page range is too large" }
        require(playerLimit > 0) { "playerLimit must be positive" }
        require(matchesPerPlayer in MIN_MATCHES_PER_PLAYER..MAX_MATCHES_PER_PLAYER) {
            "matchesPerPlayer must be between $MIN_MATCHES_PER_PLAYER and $MAX_MATCHES_PER_PLAYER"
        }
    }

    private companion object {
        val SUPPORTED_TIERS = setOf("IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND")
        val SUPPORTED_DIVISIONS = setOf("I", "II", "III", "IV")
        const val MIN_MATCHES_PER_PLAYER = 1
        const val MAX_MATCHES_PER_PLAYER = 100
    }
}
