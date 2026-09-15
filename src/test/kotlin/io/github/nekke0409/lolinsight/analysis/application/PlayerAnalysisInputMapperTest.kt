package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlayerAnalysisInputMapperTest {
    private val mapper = PlayerAnalysisInputMapper()

    @Test
    fun `maps an available POSITION comparison with role-level statistics`() {
        val input = mapper.map(feature(positionAvailable(playerValue = 12.1, benchmarkMean = 11.1)))

        val comparison = input.comparisons.single()
        assertEquals(BenchmarkScope.POSITION, comparison.scope)
        assertEquals("MIDDLE", comparison.position)
        assertNull(comparison.championId)
        assertEquals(12, comparison.userGames)
        assertEquals("KR", comparison.benchmarkCohort.region)
        assertEquals(420, comparison.benchmarkCohort.queueId)
        assertEquals("GOLD", comparison.benchmarkCohort.tier)
        assertEquals("I", comparison.benchmarkCohort.division)
        assertEquals("MIDDLE", comparison.benchmarkCohort.position)
        assertNull(comparison.benchmarkCohort.championId)
        assertEquals(40, comparison.benchmarkSampleCount)
        assertEquals(13, comparison.benchmarkUniquePlayerCount)
        assertMetricValues(comparison, playerValue = 12.1, benchmarkMean = 11.1)
    }

    @Test
    fun `maps an available CHAMPION_POSITION comparison with champion-position statistics`() {
        val input = mapper.map(feature(championPositionAvailable(playerValue = 2.1, benchmarkMean = 1.1)))

        val comparison = input.comparisons.single()
        assertEquals(BenchmarkScope.CHAMPION_POSITION, comparison.scope)
        assertEquals("MIDDLE", comparison.position)
        assertEquals(103, comparison.championId)
        assertEquals(7, comparison.userGames)
        assertEquals(103, comparison.benchmarkCohort.championId)
        assertEquals(35, comparison.benchmarkSampleCount)
        assertEquals(11, comparison.benchmarkUniquePlayerCount)
        assertMetricValues(comparison, playerValue = 2.1, benchmarkMean = 1.1)
    }

    @Test
    fun `keeps both available scopes in deterministic order without mixing their values`() {
        val feature =
            feature(
                positionAvailable(playerValue = 12.1, benchmarkMean = 11.1),
                championPositionAvailable(playerValue = 2.1, benchmarkMean = 1.1),
                championPositionInsufficient(),
            )

        val input = mapper.map(feature)

        assertEquals(listOf(BenchmarkScope.POSITION, BenchmarkScope.CHAMPION_POSITION), input.comparisons.map { it.scope })
        assertMetricValues(input.comparisons[0], playerValue = 12.1, benchmarkMean = 11.1)
        assertMetricValues(input.comparisons[1], playerValue = 2.1, benchmarkMean = 1.1)
    }

    @Test
    fun `filters every unavailable comparison from the OpenAI input`() {
        val input =
            mapper.map(
                feature(
                    positionAvailable(playerValue = 12.1, benchmarkMean = 11.1),
                    championPositionInsufficient(),
                ),
            )

        assertEquals(listOf(BenchmarkScope.POSITION), input.comparisons.map { it.scope })

        val serialized = JsonMapper.builder().build().writeValueAsString(input)
        assertFalse(serialized.contains("INSUFFICIENT_USER_SAMPLE"))
        assertFalse(serialized.contains("puuid", ignoreCase = true))
        assertFalse(serialized.contains("matchId", ignoreCase = true))
        assertFalse(serialized.contains("apiKey", ignoreCase = true))
        assertFalse(serialized.contains("rawMatch", ignoreCase = true))
    }

    @Test
    fun `enforces nullable champion identity from the explicit scope`() {
        val position = mapper.map(feature(positionAvailable(playerValue = 12.1, benchmarkMean = 11.1))).comparisons.single()
        val championPosition =
            mapper.map(feature(championPositionAvailable(playerValue = 2.1, benchmarkMean = 1.1))).comparisons.single()

        assertFailsWith<IllegalArgumentException> { position.copy(scope = BenchmarkScope.CHAMPION_POSITION) }
        assertFailsWith<IllegalArgumentException> { championPosition.copy(scope = BenchmarkScope.POSITION) }
    }

    private fun assertMetricValues(
        comparison: AnalysisComparisonInput,
        playerValue: Double,
        benchmarkMean: Double,
    ) {
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
            comparison.metrics.map { it.name },
        )
        comparison.metrics.forEach { metric ->
            assertEquals(playerValue, metric.playerValue)
            assertEquals(benchmarkMean, metric.benchmarkMean)
            assertEquals(benchmarkMean + 0.1, metric.benchmarkMedian)
            assertEquals(1.0, metric.differenceFromMean)
            assertEquals(0.9, metric.differenceFromMedian)
            assertEquals(benchmarkMean - 0.5, metric.benchmarkP25)
            assertEquals(benchmarkMean + 0.5, metric.benchmarkP75)
            assertEquals(benchmarkMean + 1.0, metric.benchmarkP90)
        }
    }

    private fun feature(vararg comparisons: PlayerCohortComparison): PlayerComparisonFeature =
        PlayerComparisonFeature(
            rankContext = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z")),
            comparisons = comparisons.toList(),
        )

    private fun positionAvailable(
        playerValue: Double,
        benchmarkMean: Double,
    ): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.POSITION,
            position = "MIDDLE",
            championId = null,
            userGames = 12,
            status = PlayerCohortComparisonStatus.AVAILABLE,
            benchmarkCohort = BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
            benchmarkSampleCount = 40,
            benchmarkUniquePlayerCount = 13,
            metrics = metrics(playerValue, benchmarkMean),
        )

    private fun championPositionAvailable(
        playerValue: Double,
        benchmarkMean: Double,
    ): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.CHAMPION_POSITION,
            position = "MIDDLE",
            championId = 103,
            userGames = 7,
            status = PlayerCohortComparisonStatus.AVAILABLE,
            benchmarkCohort = BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", "MIDDLE", 103),
            benchmarkSampleCount = 35,
            benchmarkUniquePlayerCount = 11,
            metrics = metrics(playerValue, benchmarkMean),
        )

    private fun championPositionInsufficient(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.CHAMPION_POSITION,
            position = "TOP",
            championId = 99,
            userGames = 4,
            status = PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE,
            benchmarkCohort = BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", "TOP", 99),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics = null,
        )

    private fun metrics(
        playerValue: Double,
        benchmarkMean: Double,
    ): PlayerComparisonMetrics =
        PlayerComparisonMetrics(
            kda = metric(playerValue, benchmarkMean),
            csPerMinute = metric(playerValue, benchmarkMean),
            goldPerMinute = metric(playerValue, benchmarkMean),
            damagePerMinute = metric(playerValue, benchmarkMean),
            visionPerMinute = metric(playerValue, benchmarkMean),
            killParticipation = metric(playerValue, benchmarkMean),
            damageShare = metric(playerValue, benchmarkMean),
        )

    private fun metric(
        playerValue: Double,
        benchmarkMean: Double,
    ): MetricComparison =
        MetricComparison(
            playerValue = playerValue,
            benchmarkMean = benchmarkMean,
            benchmarkMedian = benchmarkMean + 0.1,
            differenceFromMean = 1.0,
            differenceFromMedian = 0.9,
            benchmarkP25 = benchmarkMean - 0.5,
            benchmarkP75 = benchmarkMean + 0.5,
            benchmarkP90 = benchmarkMean + 1.0,
        )
}
