package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.github.nekke0409.lolinsight.analysis.application.AvailablePlayerCohortComparison
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisBenchmarkCohort
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisLimitation
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetric
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetricName

object TestPlayerAnalysisInput {
    fun input(): PlayerAnalysisInput =
        PlayerAnalysisInput(
            availableComparisons =
                listOf(
                    AvailablePlayerCohortComparison(
                        championId = 103,
                        position = "MIDDLE",
                        userGames = 5,
                        benchmarkCohort = PlayerAnalysisBenchmarkCohort("KR", 420, "GOLD", "I", "MIDDLE", 103),
                        benchmarkSampleCount = 30,
                        benchmarkUniquePlayerCount = 10,
                        metrics =
                            listOf(
                                PlayerAnalysisMetric(
                                    PlayerAnalysisMetricName.CS_PER_MINUTE,
                                    7.2,
                                    6.7,
                                    6.8,
                                    0.5,
                                    0.4,
                                    6.3,
                                    7.0,
                                    7.4,
                                ),
                            ),
                    ),
                ),
            excludedComparisonSummary = emptyList(),
            analysisLimitations = listOf(PlayerAnalysisLimitation("MATCH_LEVEL_BENCHMARK", "match-level")),
        )
}
