package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
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
import kotlin.test.assertFailsWith

class PlayerAnalysisServiceTest {
    private val playerComparisonFeatureService = mock(PlayerComparisonFeatureService::class.java)
    private val playerAnalysisInputMapper = mock(PlayerAnalysisInputMapper::class.java)
    private val playerAnalysisGenerator = mock(PlayerAnalysisGenerator::class.java)
    private val playerAnalysisResultCache = mock(PlayerAnalysisResultCache::class.java)
    private val service =
        PlayerAnalysisService(
            playerComparisonFeatureService = playerComparisonFeatureService,
            playerAnalysisInputMapper = playerAnalysisInputMapper,
            playerAnalysisGenerator = playerAnalysisGenerator,
            playerAnalysisResultCache = playerAnalysisResultCache,
        )

    @Test
    fun `does not call the generator when the user is unranked`() {
        stubFeature(
            PlayerComparisonFeature(
                rankContext = null,
                comparisons = listOf(unrankedPositionComparison()),
            ),
        )

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.UNRANKED, response.status)
        assertEquals(null, response.analysis)
        verifyNoInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    @Test
    fun `calls the generator exactly once when only POSITION is available`() {
        assertAnalyzedWithSingleGeneratorCall(feature(positionAvailable()))
    }

    @Test
    fun `calls the generator exactly once when only CHAMPION_POSITION is available`() {
        assertAnalyzedWithSingleGeneratorCall(feature(championPositionAvailable()))
    }

    @Test
    fun `calls the generator exactly once when both scopes are available`() {
        assertAnalyzedWithSingleGeneratorCall(feature(positionAvailable(), championPositionAvailable()))
    }

    @Test
    fun `does not call the generator when neither scope is available`() {
        stubFeature(feature(positionInsufficient(), championPositionInsufficient()))

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, response.status)
        assertEquals(null, response.analysis)
        verifyNoInteractions(playerAnalysisInputMapper, playerAnalysisGenerator)
    }

    @Test
    fun `returns a completed result cache hit without generating again`() {
        val feature = feature(positionAvailable())
        val input = analysisInput()
        val result = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
        stubFeature(feature)
        `when`(playerAnalysisInputMapper.map(feature)).thenReturn(input)
        `when`(playerAnalysisResultCache.find(input)).thenReturn(result)

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponse(PlayerAnalysisResponseStatus.ANALYZED, result), response)
        verify(playerAnalysisResultCache).find(input)
        verifyNoInteractions(playerAnalysisGenerator)
    }

    @Test
    fun `does not cache a failed generation`() {
        val feature = feature(positionAvailable())
        val input = analysisInput()
        stubFeature(feature)
        `when`(playerAnalysisInputMapper.map(feature)).thenReturn(input)
        `when`(playerAnalysisGenerator.generate(input)).thenThrow(IllegalStateException("provider failure"))

        assertFailsWith<IllegalStateException> {
            service.analyze(GAME_NAME, TAG_LINE, 0, 20)
        }

        verify(playerAnalysisResultCache).find(input)
        verify(playerAnalysisGenerator).generate(input)
        verifyNoMoreInteractions(playerAnalysisResultCache)
    }

    private fun assertAnalyzedWithSingleGeneratorCall(feature: PlayerComparisonFeature) {
        val input = analysisInput()
        val result = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
        stubFeature(feature)
        `when`(playerAnalysisInputMapper.map(feature)).thenReturn(input)
        `when`(playerAnalysisGenerator.generate(input)).thenReturn(result)

        val response = service.analyze(GAME_NAME, TAG_LINE, 0, 20)

        assertEquals(PlayerAnalysisResponseStatus.ANALYZED, response.status)
        assertEquals(result, response.analysis)
        verify(playerAnalysisInputMapper).map(feature)
        verify(playerAnalysisResultCache).find(input)
        verify(playerAnalysisGenerator).generate(input)
        verify(playerAnalysisResultCache).store(input, result)
        verifyNoMoreInteractions(playerAnalysisInputMapper, playerAnalysisGenerator, playerAnalysisResultCache)
    }

    private fun stubFeature(feature: PlayerComparisonFeature) {
        `when`(playerComparisonFeatureService.buildFeature(GAME_NAME, TAG_LINE, 0, 20)).thenReturn(feature)
    }

    private fun feature(vararg comparisons: PlayerCohortComparison): PlayerComparisonFeature =
        PlayerComparisonFeature(
            rankContext = RANK_CONTEXT,
            comparisons = comparisons.toList(),
        )

    private fun positionAvailable(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.POSITION,
            position = "MIDDLE",
            championId = null,
            userGames = 8,
            status = PlayerCohortComparisonStatus.AVAILABLE,
            benchmarkCohort = BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics = metrics(),
        )

    private fun championPositionAvailable(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.CHAMPION_POSITION,
            position = "MIDDLE",
            championId = 103,
            userGames = 5,
            status = PlayerCohortComparisonStatus.AVAILABLE,
            benchmarkCohort = BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", "MIDDLE", 103),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics = metrics(),
        )

    private fun positionInsufficient(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.POSITION,
            position = "MIDDLE",
            championId = null,
            userGames = 4,
            status = PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE,
            benchmarkCohort = BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
            benchmarkSampleCount = 30,
            benchmarkUniquePlayerCount = 10,
            metrics = null,
        )

    private fun championPositionInsufficient(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.CHAMPION_POSITION,
            position = "MIDDLE",
            championId = 103,
            userGames = 4,
            status = PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE,
            benchmarkCohort = BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", "MIDDLE", 103),
            benchmarkSampleCount = 10,
            benchmarkUniquePlayerCount = 5,
            metrics = null,
        )

    private fun unrankedPositionComparison(): PlayerCohortComparison =
        PlayerCohortComparison(
            scope = BenchmarkScope.POSITION,
            position = "MIDDLE",
            championId = null,
            userGames = 4,
            status = PlayerCohortComparisonStatus.UNRANKED,
            benchmarkCohort = null,
            benchmarkSampleCount = 0,
            benchmarkUniquePlayerCount = 0,
            metrics = null,
        )

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
            comparisons =
                listOf(
                    AnalysisComparisonInput(
                        scope = BenchmarkScope.POSITION,
                        position = "MIDDLE",
                        championId = null,
                        userGames = 5,
                        benchmarkCohort = PlayerAnalysisBenchmarkCohort("KR", 420, "GOLD", "I", "MIDDLE", null),
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
            analysisLimitations = listOf(PlayerAnalysisLimitation("MATCH_LEVEL_BENCHMARK", "match-level")),
        )

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        val RANK_CONTEXT = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))
    }
}
