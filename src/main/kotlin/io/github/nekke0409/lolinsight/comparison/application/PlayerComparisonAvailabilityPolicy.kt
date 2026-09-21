package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import org.springframework.stereotype.Component

/** Shared comparison gate for a user's scoped sample and the peer benchmark availability. */
@Component
class PlayerComparisonAvailabilityPolicy {
    fun determine(
        userGames: Int,
        benchmarkAvailability: BenchmarkAvailability,
    ): PlayerCohortComparisonStatus {
        require(userGames >= 0) { "userGames cannot be negative" }

        if (userGames < MINIMUM_USER_GAMES_FOR_COMPARISON) {
            return PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE
        }

        return when (benchmarkAvailability) {
            BenchmarkAvailability.NO_DATA -> PlayerCohortComparisonStatus.BENCHMARK_NO_DATA
            BenchmarkAvailability.INSUFFICIENT_SAMPLE -> PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE
            BenchmarkAvailability.AVAILABLE -> PlayerCohortComparisonStatus.AVAILABLE
        }
    }

    companion object {
        const val MINIMUM_USER_GAMES_FOR_COMPARISON = 5
    }
}
