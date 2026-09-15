package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.github.nekke0409.lolinsight.analysis.application.AnalysisComparisonInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisBenchmarkCohort
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisLimitation
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetric
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetricName
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope

object TestPlayerAnalysisInput {
    fun input(): PlayerAnalysisInput =
        PlayerAnalysisInput(
            comparisons =
                listOf(
                    comparison(
                        scope = BenchmarkScope.POSITION,
                        championId = null,
                        playerValue = 7.2,
                        benchmarkMean = 6.7,
                    ),
                    comparison(
                        scope = BenchmarkScope.CHAMPION_POSITION,
                        championId = 103,
                        playerValue = 5.2,
                        benchmarkMean = 4.7,
                    ),
                ),
            analysisLimitations = listOf(PlayerAnalysisLimitation("MATCH_LEVEL_BENCHMARK", "match-level")),
        )

    private fun comparison(
        scope: BenchmarkScope,
        championId: Int?,
        playerValue: Double,
        benchmarkMean: Double,
    ): AnalysisComparisonInput =
        AnalysisComparisonInput(
            scope = scope,
            position = "MIDDLE",
            championId = championId,
            userGames = 5,
            benchmarkCohort = PlayerAnalysisBenchmarkCohort("KR", 420, "GOLD", "I", "MIDDLE", championId),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics =
                listOf(
                    PlayerAnalysisMetric(
                        PlayerAnalysisMetricName.CS_PER_MINUTE,
                        playerValue,
                        benchmarkMean,
                        benchmarkMean + 0.1,
                        0.5,
                        0.4,
                        benchmarkMean - 0.4,
                        benchmarkMean + 0.3,
                        benchmarkMean + 0.7,
                    ),
                ),
        )
}
