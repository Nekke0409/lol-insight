package io.github.nekke0409.lolinsight.analysis.application

data class PlayerAnalysisInput(
    val availableComparisons: List<AvailablePlayerCohortComparison>,
    val excludedComparisonSummary: List<ExcludedPlayerCohortComparison>,
    val analysisLimitations: List<PlayerAnalysisLimitation>,
) {
    init {
        require(availableComparisons.isNotEmpty()) { "availableComparisons must not be empty" }
    }
}

data class AvailablePlayerCohortComparison(
    val championId: Int,
    val position: String,
    val userGames: Int,
    val benchmarkCohort: PlayerAnalysisBenchmarkCohort,
    val benchmarkSampleCount: Long,
    val benchmarkUniquePlayerCount: Long,
    val metrics: List<PlayerAnalysisMetric>,
) {
    init {
        require(championId > 0) { "championId must be positive" }
        require(position.isNotBlank()) { "position must not be blank" }
        require(userGames > 0) { "userGames must be positive" }
        require(benchmarkSampleCount > 0) { "benchmarkSampleCount must be positive" }
        require(benchmarkUniquePlayerCount > 0) { "benchmarkUniquePlayerCount must be positive" }
        require(benchmarkUniquePlayerCount <= benchmarkSampleCount) {
            "benchmarkUniquePlayerCount cannot exceed benchmarkSampleCount"
        }
        require(metrics.isNotEmpty()) { "metrics must not be empty" }
    }
}

data class ExcludedPlayerCohortComparison(
    val championId: Int,
    val position: String,
    val userGames: Int,
    val status: String,
) {
    init {
        require(championId > 0) { "championId must be positive" }
        require(position.isNotBlank()) { "position must not be blank" }
        require(userGames > 0) { "userGames must be positive" }
        require(status.isNotBlank()) { "status must not be blank" }
    }
}

data class PlayerAnalysisBenchmarkCohort(
    val region: String,
    val queueId: Int,
    val tier: String,
    val division: String,
    val position: String,
    val championId: Int,
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
