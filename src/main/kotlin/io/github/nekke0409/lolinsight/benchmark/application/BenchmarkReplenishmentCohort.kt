package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverageScope
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue

/**
 * A deliberately small, League-V4-discoverable cohort. Region and queue are fixed for the
 * current benchmark corpus; adding another discovery route is a separate decision.
 */
data class BenchmarkReplenishmentCohort(
    val tier: String,
    val division: String,
) {
    init {
        require(tier in SUPPORTED_TIERS) { "tier must be supported by the current League-V4 discovery route" }
        require(division in SUPPORTED_DIVISIONS) { "division must be supported by the current League-V4 discovery route" }
    }

    fun coverageScope(): BenchmarkCohortCoverageScope =
        BenchmarkCohortCoverageScope(
            region = KR_REGION,
            queueId = RankedSoloQueue.ID,
            tier = tier,
            division = division,
        )

    companion object {
        const val KR_REGION = "KR"
        val SUPPORTED_TIERS = setOf("IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND")
        val SUPPORTED_DIVISIONS = setOf("I", "II", "III", "IV")
    }
}
