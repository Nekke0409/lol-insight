package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.comparison.application.MetricComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeature
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonMetrics
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals

class PlayerAnalysisServiceTest {
    private val playerComparisonFeatureService = mock(PlayerComparisonFeatureService::class.java)
    private val playerAnalysisInputMapper = mock(PlayerAnalysisInputMapper::class.java)
    private val playerAnalysisGenerator = mock(PlayerAnalysisGenerator::class.java)
    private val service =
        PlayerAnalysisService(
            playerComparisonFeatureService = playerComparisonFeatureService,
            playerAnalysisInputMapper = playerAnalysisInputMapper,
            playerAnalysisGenerator = playerAnalysisGenerator,
        )

    @Test
    fun `does not call the generator when the user is unranked`() {
        stubFeature(feature(rankContext = null, statuses = listOf(PlayerCohortComparisonStatus.UNRANKED)))

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.UNRANKED, response.status)
        assertEquals(null, response.analysis)
        verifyNoInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    @Test
    fun `does not call the generator when only user sample insufficient cohorts exist`() {
        stubFeature(feature(statuses = listOf(PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE)))

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, response.status)
        verifyNoInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    @Test
    fun `does not call the generator when only benchmark unavailable cohorts exist`() {
        stubFeature(
            feature(
                statuses =
                    listOf(
                        PlayerCohortComparisonStatus.BENCHMARK_NO_DATA,
                        PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE,
                    ),
            ),
        )

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, response.status)
        verifyNoInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    @Test
    fun `calls the generator exactly once when one or more cohorts are available`() {
        val feature =
            feature(
                statuses =
                    listOf(
                        PlayerCohortComparisonStatus.AVAILABLE,
                        PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE,
                    ),
            )
        val input = analysisInput()
        val result = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
        stubFeature(feature)
        `when`(playerAnalysisInputMapper.map(feature)).thenReturn(input)
        `when`(playerAnalysisGenerator.generate(input)).thenReturn(result)

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.ANALYZED, response.status)
        assertEquals(result, response.analysis)
        verify(playerAnalysisInputMapper).map(feature)
        verify(playerAnalysisGenerator).generate(input)
        verifyNoMoreInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    private fun stubFeature(feature: PlayerComparisonFeature) {
        `when`(playerComparisonFeatureService.buildFeature(GAME_NAME, TAG_LINE, 0, 20)).thenReturn(feature)
    }

    private fun feature(
        rankContext: PlayerRankContext? = RANK_CONTEXT,
        statuses: List<PlayerCohortComparisonStatus>,
    ): PlayerComparisonFeature =
        PlayerComparisonFeature(
            rankContext = rankContext,
            comparisons =
                statuses.mapIndexed { index, status ->
                    comparison(status = status, championId = 100 + index)
                },
        )

    private fun comparison(
        status: PlayerCohortComparisonStatus,
        championId: Int,
    ): PlayerCohortComparison {
        val cohort = BenchmarkCohort("KR", 420, "GOLD", "I", "MIDDLE", championId)
        return PlayerCohortComparison(
            championId = championId,
            position = "MIDDLE",
            userGames = 5,
            status = status,
            benchmarkCohort = if (status == PlayerCohortComparisonStatus.UNRANKED) null else cohort,
            benchmarkSampleCount =
                when (status) {
                    PlayerCohortComparisonStatus.AVAILABLE,
                    PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE,
                    -> 10
                    else -> 0
                },
            benchmarkUniquePlayerCount =
                when (status) {
                    PlayerCohortComparisonStatus.AVAILABLE,
                    PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE,
                    -> 5
                    else -> 0
                },
            metrics = if (status == PlayerCohortComparisonStatus.AVAILABLE) metrics() else null,
        )
    }

    private fun metrics(): PlayerComparisonMetrics =
        PlayerComparisonMetrics(
            kda = metric(),
            csPerMinute = metric(),
            goldPerMinute = metric(),
            damagePerMinute = metric(),
            visionPerMinute = metric(),
            killParticipation = metric(),
            damageShare = metric(),
        )

    private fun metric(): MetricComparison = MetricComparison(7.2, 6.7, 6.8, 0.5, 0.4, 6.3, 7.0, 7.4)

    private fun analysisInput(): PlayerAnalysisInput =
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
                                    PlayerAnalysisMetricName.KDA,
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

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        val RANK_CONTEXT = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))
    }
}
