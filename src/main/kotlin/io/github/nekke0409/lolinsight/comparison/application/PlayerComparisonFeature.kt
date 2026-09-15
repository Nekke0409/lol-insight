package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext

data class PlayerComparisonFeature(
    val rankContext: PlayerRankContext?,
    val comparisons: List<PlayerCohortComparison>,
) {
    init {
        require(comparisons == comparisons.sortedWith(COMPARISON_ORDER)) {
            "comparisons must use the deterministic cohort order"
        }
        if (rankContext == null) {
            require(comparisons.all { it.status == PlayerCohortComparisonStatus.UNRANKED }) {
                "a feature without a rank context must be unranked"
            }
        } else {
            require(comparisons.none { it.status == PlayerCohortComparisonStatus.UNRANKED }) {
                "a feature with a rank context must not contain unranked comparisons"
            }
        }
    }

    private companion object {
        val COMPARISON_ORDER =
            compareBy<PlayerCohortComparison> { it.scope }
                .thenByDescending { it.userGames }
                .thenBy { it.position }
                .thenBy { it.championId ?: 0 }
    }
}

data class PlayerCohortComparison(
    val scope: BenchmarkScope,
    val position: String,
    val championId: Int?,
    val userGames: Int,
    val status: PlayerCohortComparisonStatus,
    val benchmarkCohort: BenchmarkCohort?,
    val benchmarkSampleCount: Long,
    val benchmarkUniquePlayerCount: Long,
    val metrics: PlayerComparisonMetrics?,
) {
    init {
        require(position.isNotBlank()) { "position must not be blank" }
        require(userGames > 0) { "userGames must be positive" }
        require(benchmarkSampleCount >= 0) { "benchmarkSampleCount cannot be negative" }
        require(benchmarkUniquePlayerCount >= 0) { "benchmarkUniquePlayerCount cannot be negative" }
        require(benchmarkUniquePlayerCount <= benchmarkSampleCount) {
            "benchmarkUniquePlayerCount cannot exceed benchmarkSampleCount"
        }

        when (scope) {
            BenchmarkScope.POSITION -> require(championId == null) { "POSITION scope must not include championId" }
            BenchmarkScope.CHAMPION_POSITION ->
                require(championId != null && championId > 0) {
                    "CHAMPION_POSITION scope requires a positive championId"
                }
        }

        if (benchmarkCohort != null) {
            require(benchmarkCohort.scope == scope) { "benchmark cohort scope must match comparison scope" }
            require(benchmarkCohort.position == position) { "benchmark cohort position must match comparison position" }
            require(benchmarkCohort.championId == championId) { "benchmark cohort championId must match comparison championId" }
        }

        when (status) {
            PlayerCohortComparisonStatus.UNRANKED -> {
                require(benchmarkCohort == null) { "UNRANKED must not include a benchmark cohort" }
                require(benchmarkSampleCount == 0L) { "UNRANKED must not include benchmark samples" }
                require(benchmarkUniquePlayerCount == 0L) { "UNRANKED must not include benchmark players" }
            }

            PlayerCohortComparisonStatus.BENCHMARK_NO_DATA -> {
                require(benchmarkCohort != null) { "BENCHMARK_NO_DATA requires a benchmark cohort" }
                require(benchmarkSampleCount == 0L) { "BENCHMARK_NO_DATA must not include benchmark samples" }
                require(benchmarkUniquePlayerCount == 0L) { "BENCHMARK_NO_DATA must not include benchmark players" }
            }

            PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE -> {
                require(benchmarkCohort != null) { "$status requires a benchmark cohort" }
                require(benchmarkSampleCount > 0) { "$status requires benchmark samples" }
            }

            PlayerCohortComparisonStatus.AVAILABLE -> {
                require(benchmarkCohort != null) { "$status requires a benchmark cohort" }
                require(benchmarkSampleCount > 0) { "$status requires benchmark samples" }
            }

            PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE ->
                require(benchmarkCohort != null) { "INSUFFICIENT_USER_SAMPLE requires a benchmark cohort" }
        }

        if (status == PlayerCohortComparisonStatus.AVAILABLE) {
            require(metrics != null) { "AVAILABLE requires metric comparisons" }
        } else {
            require(metrics == null) { "$status must not include metric comparisons" }
        }
    }
}

enum class PlayerCohortComparisonStatus {
    AVAILABLE,
    UNRANKED,
    INSUFFICIENT_USER_SAMPLE,
    BENCHMARK_NO_DATA,
    BENCHMARK_INSUFFICIENT_SAMPLE,
}

data class PlayerComparisonMetrics(
    val kda: MetricComparison,
    val csPerMinute: MetricComparison,
    val goldPerMinute: MetricComparison,
    val damagePerMinute: MetricComparison,
    val visionPerMinute: MetricComparison,
    val killParticipation: MetricComparison,
    val damageShare: MetricComparison,
)

data class MetricComparison(
    val playerValue: Double,
    val benchmarkMean: Double,
    val benchmarkMedian: Double,
    val differenceFromMean: Double,
    val differenceFromMedian: Double,
    val benchmarkP25: Double,
    val benchmarkP75: Double,
    val benchmarkP90: Double,
) {
    init {
        require(
            listOf(
                playerValue,
                benchmarkMean,
                benchmarkMedian,
                differenceFromMean,
                differenceFromMedian,
                benchmarkP25,
                benchmarkP75,
                benchmarkP90,
            ).all(Double::isFinite),
        ) { "metric comparison values must be finite" }
    }
}
