package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.comparison.application.MetricComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeature
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerAnalysisInputMapperTest {
    private val mapper = PlayerAnalysisInputMapper()

    @Test
    fun `maps only available exact cohort metrics and only safe excluded summaries`() {
        val input = mapper.map(feature())

        val available = input.availableComparisons.single()
        assertEquals(103, available.championId)
        assertEquals("MIDDLE", available.position)
        assertEquals(7, available.userGames)
        assertEquals("KR", available.benchmarkCohort.region)
        assertEquals(420, available.benchmarkCohort.queueId)
        assertEquals("GOLD", available.benchmarkCohort.tier)
        assertEquals("I", available.benchmarkCohort.division)
        assertEquals(30, available.benchmarkSampleCount)
        assertEquals(10, available.benchmarkUniquePlayerCount)
        assertEquals(
            listOf(
                PlayerAnalysisMetricName.KDA,
                PlayerAnalysisMetricName.CS_PER_MINUTE,
                PlayerAnalysisMetricName.GOLD_PER_MINUTE,
                PlayerAnalysisMetricName.DAMAGE_PER_MINUTE,
                PlayerAnalysisMetricName.VISION_PER_MINUTE,
                PlayerAnalysisMetricName.KILL_PARTICIPATION,
                PlayerAnalysisMetricName.DAMAGE_SHARE,
            ),
            available.metrics.map { it.name },
        )
        available.metrics.forEach { metric ->
            assertEquals(7.2, metric.playerValue)
            assertEquals(6.7, metric.benchmarkMean)
            assertEquals(6.8, metric.benchmarkMedian)
            assertEquals(0.5, metric.differenceFromMean)
            assertEquals(0.4, metric.differenceFromMedian)
            assertEquals(6.3, metric.benchmarkP25)
            assertEquals(7.0, metric.benchmarkP75)
            assertEquals(7.4, metric.benchmarkP90)
        }
        assertEquals(
            ExcludedPlayerCohortComparison(99, "TOP", 4, "INSUFFICIENT_USER_SAMPLE"),
            input.excludedComparisonSummary.single(),
        )

        val serialized = JsonMapper.builder().build().writeValueAsString(input)
        assertFalse(serialized.contains("puuid", ignoreCase = true))
        assertFalse(serialized.contains("matchId", ignoreCase = true))
        assertFalse(serialized.contains("apiKey", ignoreCase = true))
        assertFalse(serialized.contains("rawMatch", ignoreCase = true))
    }

    private fun feature(): PlayerComparisonFeature =
        PlayerComparisonFeature(
            rankContext = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z")),
            comparisons =
                listOf(
                    availableComparison(),
                    PlayerCohortComparison(
                        championId = 99,
                        position = "TOP",
                        userGames = 4,
                        status = PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE,
                        benchmarkCohort = BenchmarkCohort("KR", 420, "GOLD", "I", "TOP", 99),
                        benchmarkSampleCount = 30,
                        benchmarkUniquePlayerCount = 10,
                        metrics = null,
                    ),
                ),
        )

    private fun availableComparison(): PlayerCohortComparison =
        PlayerCohortComparison(
            championId = 103,
            position = "MIDDLE",
            userGames = 7,
            status = PlayerCohortComparisonStatus.AVAILABLE,
            benchmarkCohort = BenchmarkCohort("KR", 420, "GOLD", "I", "MIDDLE", 103),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics =
                PlayerComparisonMetrics(
                    kda = metric(),
                    csPerMinute = metric(),
                    goldPerMinute = metric(),
                    damagePerMinute = metric(),
                    visionPerMinute = metric(),
                    killParticipation = metric(),
                    damageShare = metric(),
                ),
        )

    private fun metric(): MetricComparison = MetricComparison(7.2, 6.7, 6.8, 0.5, 0.4, 6.3, 7.0, 7.4)
}
