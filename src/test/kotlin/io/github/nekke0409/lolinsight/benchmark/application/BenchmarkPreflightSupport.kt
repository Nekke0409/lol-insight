package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonAvailabilityPolicy
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext

internal data class BenchmarkPreflightSettings(
    val gameName: String,
    val tagLine: String,
    val expectedCohort: BenchmarkReplenishmentCohort,
    val position: String,
) {
    companion object {
        fun fromEnvironment(): BenchmarkPreflightSettings = from(::environmentValue)

        fun from(lookup: (String) -> String?): BenchmarkPreflightSettings {
            val gameName = requiredValue(lookup, "TARGET_GAME_NAME")
            val tagLine = requiredValue(lookup, "TARGET_TAG_LINE")
            requiredValue(lookup, "RIOT_API_KEY")
            val expectedCohort =
                BenchmarkReplenishmentCohort(
                    tier = requiredValue(lookup, "BENCHMARK_PREFLIGHT_EXPECTED_TIER"),
                    division = requiredValue(lookup, "BENCHMARK_PREFLIGHT_EXPECTED_DIVISION"),
                )
            val position = requiredValue(lookup, "BENCHMARK_PREFLIGHT_POSITION")
            require(position in BenchmarkReplenishmentPlanner.POSITIONS) {
                "BENCHMARK_PREFLIGHT_POSITION must be a supported benchmark position"
            }

            return BenchmarkPreflightSettings(gameName, tagLine, expectedCohort, position)
        }

        private fun environmentValue(name: String): String? = System.getenv(name)

        private fun requiredValue(
            lookup: (String) -> String?,
            name: String,
        ): String = requireNotNull(lookup(name)?.trim()?.takeIf(String::isNotEmpty)) { "$name must be set" }
    }
}

internal data class BenchmarkPreflightAssessment(
    val actualRank: PlayerRankContext?,
    val expectedCohort: BenchmarkReplenishmentCohort,
    val userPositionGames: Int,
    val selfExcludedAvailability: BenchmarkAvailability,
    val comparisonStatus: PlayerCohortComparisonStatus?,
    val ready: Boolean,
    val reason: String,
) {
    companion object {
        fun assess(
            actualRank: PlayerRankContext?,
            expectedCohort: BenchmarkReplenishmentCohort,
            userPositionGames: Int,
            selfExcludedAvailability: BenchmarkAvailability,
            comparisonAvailabilityPolicy: PlayerComparisonAvailabilityPolicy,
        ): BenchmarkPreflightAssessment {
            require(userPositionGames >= 0) { "userPositionGames cannot be negative" }

            val rankMatches =
                actualRank?.tier == expectedCohort.tier && actualRank.division == expectedCohort.division
            val comparisonStatus =
                comparisonAvailabilityPolicy.determine(userPositionGames, selfExcludedAvailability)
            val reason =
                when {
                    actualRank == null -> "current Solo rank is UNRANKED"
                    !rankMatches -> "current Solo rank does not match the expected cohort"
                    comparisonStatus == PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE ->
                        "target position has insufficient Ranked Solo games"

                    comparisonStatus == PlayerCohortComparisonStatus.BENCHMARK_NO_DATA ->
                        "self-excluded benchmark has no valid samples"

                    comparisonStatus == PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE ->
                        "self-excluded benchmark is below the availability threshold"

                    comparisonStatus == PlayerCohortComparisonStatus.AVAILABLE -> "ready"
                    else -> error("unexpected preflight comparison status")
                }

            return BenchmarkPreflightAssessment(
                actualRank = actualRank,
                expectedCohort = expectedCohort,
                userPositionGames = userPositionGames,
                selfExcludedAvailability = selfExcludedAvailability,
                comparisonStatus = comparisonStatus,
                ready = rankMatches && comparisonStatus == PlayerCohortComparisonStatus.AVAILABLE,
                reason = reason,
            )
        }
    }

    val actualRankLabel: String
        get() = actualRank?.let { "${it.tier} ${it.division}" } ?: "UNRANKED"

    val rankMatchesExpectedCohort: Boolean
        get() = actualRank?.tier == expectedCohort.tier && actualRank.division == expectedCohort.division
}
