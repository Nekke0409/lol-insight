package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.github.nekke0409.lolinsight.analysis.application.AvailablePlayerCohortComparison
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisBenchmarkCohort
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisLimitation
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetric
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetricName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains

class PlayerAnalysisPromptFactoryTest {
    private val factory = PlayerAnalysisPromptFactory(JsonMapper.builder().build())

    @Test
    fun `includes the critical comparison and Korean output constraints`() {
        val prompt = factory.create(input())

        assertContains(prompt.instructions, "한국어")
        assertContains(prompt.instructions, "top X%")
        assertContains(prompt.instructions, "player percentile")
        assertContains(prompt.instructions, "match-level observation")
        assertContains(prompt.instructions, "position이 다른 cohort")
        assertContains(prompt.instructions, "good/bad")
        assertContains(prompt.instructions, "sample eligibility")
        assertContains(prompt.instructions, "timeline")
        assertContains(prompt.instructions, "gameVersion")
        assertContains(prompt.structuredData, "availableComparisons")
        assertContains(prompt.structuredData, "differenceFromMedian")
    }

    private fun input(): PlayerAnalysisInput =
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
