package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import java.time.Duration

data class PlayerAnalysisInput(
    val comparisons: List<AnalysisComparisonInput>,
    val analysisLimitations: List<PlayerAnalysisLimitation>,
    val benchmarkFreshness: PlayerAnalysisBenchmarkFreshness = PlayerAnalysisBenchmarkFreshness(Duration.ofDays(30)),
) {
    init {
        require(comparisons.isNotEmpty()) { "comparisons must not be empty" }
        require(comparisons == comparisons.sortedWith(COMPARISON_ORDER)) {
            "comparisons must use the deterministic analysis order"
        }
        require(analysisLimitations == analysisLimitations.sortedBy(PlayerAnalysisLimitation::code)) {
            "analysisLimitations must use the deterministic code order"
        }
    }

    private companion object {
        val COMPARISON_ORDER =
            compareBy<AnalysisComparisonInput> { it.scope }
                .thenByDescending { it.userGames }
                .thenBy { it.position }
                .thenBy { it.championId ?: 0 }
    }
}

data class PlayerAnalysisBenchmarkFreshness(
    val maxSampleAge: Duration,
) {
    init {
        require(!maxSampleAge.isNegative && !maxSampleAge.isZero) { "maxSampleAge must be positive" }
    }
}

data class AnalysisComparisonInput(
    val scope: BenchmarkScope,
    val position: String,
    val championId: Int?,
    val userGames: Int,
    val benchmarkCohort: PlayerAnalysisBenchmarkCohort,
    val benchmarkSampleCount: Long,
    val benchmarkUniquePlayerCount: Long,
    val metrics: List<PlayerAnalysisMetric>,
) {
    init {
        require(position.isNotBlank()) { "position must not be blank" }
        require(userGames > 0) { "userGames must be positive" }
        require(benchmarkSampleCount > 0) { "benchmarkSampleCount must be positive" }
        require(benchmarkUniquePlayerCount > 0) { "benchmarkUniquePlayerCount must be positive" }
        require(benchmarkUniquePlayerCount <= benchmarkSampleCount) {
            "benchmarkUniquePlayerCount cannot exceed benchmarkSampleCount"
        }
        require(metrics.isNotEmpty()) { "metrics must not be empty" }
        require(benchmarkCohort.position == position) { "benchmark cohort position must match comparison position" }
        require(benchmarkCohort.championId == championId) { "benchmark cohort championId must match comparison championId" }
        require(metrics == metrics.sortedBy(PlayerAnalysisMetric::name)) {
            "metrics must use the deterministic metric order"
        }

        when (scope) {
            BenchmarkScope.POSITION -> require(championId == null) { "POSITION scope must not include championId" }
            BenchmarkScope.CHAMPION_POSITION ->
                require(championId != null && championId > 0) {
                    "CHAMPION_POSITION scope requires a positive championId"
                }
        }
    }
}

data class PlayerAnalysisBenchmarkCohort(
    val region: String,
    val queueId: Int,
    val tier: String,
    val division: String,
    val position: String,
    val championId: Int?,
)

data class PlayerAnalysisMetric(
    val name: PlayerAnalysisMetricName,
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
        ) { "metric values must be finite" }
    }
}

enum class PlayerAnalysisMetricName {
    KDA,
    CS_PER_MINUTE,
    GOLD_PER_MINUTE,
    DAMAGE_PER_MINUTE,
    VISION_PER_MINUTE,
    KILL_PARTICIPATION,
    DAMAGE_SHARE,
}

data class PlayerAnalysisLimitation(
    val code: String,
    val description: String,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
        require(description.isNotBlank()) { "description must not be blank" }
    }
}
