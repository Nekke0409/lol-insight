package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.application.AnalysisComparisonInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisBenchmarkCohort
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisGenerator
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInputMapper
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisLimitation
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetric
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisMetricName
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResultCache
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AnalysisJobWorkerCompletedResultCacheTest {
    private val comparisonFeatureService = mock(PlayerComparisonFeatureService::class.java)
    private val inputMapper = mock(PlayerAnalysisInputMapper::class.java)
    private val generator = mock(PlayerAnalysisGenerator::class.java)
    private val resultCache = InMemoryResultCache()
    private val playerAnalysisService =
        PlayerAnalysisService(comparisonFeatureService, inputMapper, generator, resultCache)
    private val lifecycleService = mock(AnalysisJobLifecycleService::class.java)
    private val inFlightRegistry = mock(AnalysisJobInFlightRegistry::class.java)
    private val worker =
        AnalysisJobWorker(
            lifecycleService,
            playerAnalysisService,
            AnalysisJobFailureCodeMapper(),
            inFlightRegistry,
        )

    @Test
    fun `reuses a synchronous completed result when the async worker sees the same effective input`() {
        `when`(comparisonFeatureService.buildFeature(GAME_NAME, TAG_LINE, 0, 20)).thenReturn(FEATURE)
        `when`(inputMapper.map(FEATURE)).thenReturn(INPUT)
        `when`(generator.generate(INPUT)).thenReturn(RESULT)
        `when`(lifecycleService.markRunningIfPending(COMMAND.jobId)).thenReturn(true)
        `when`(lifecycleService.markSucceededIfRunning(COMMAND.jobId, RESULT)).thenReturn(true)

        assertEquals(RESULT, playerAnalysisService.analyze(GAME_NAME, TAG_LINE, 0, 20).analysis)

        worker.process(COMMAND)

        verify(generator, times(1)).generate(INPUT)
        verify(lifecycleService).markSucceededIfRunning(COMMAND.jobId, RESULT)
        assertEquals(1, resultCache.storeCount)
    }

    private class InMemoryResultCache : PlayerAnalysisResultCache {
        private val values = mutableMapOf<PlayerAnalysisInput, PlayerAnalysisResult>()
        var storeCount = 0
            private set

        override fun find(input: PlayerAnalysisInput): PlayerAnalysisResult? = values[input]

        override fun store(
            input: PlayerAnalysisInput,
            result: PlayerAnalysisResult,
        ) {
            values[input] = result
            storeCount++
        }
    }

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        val RESULT = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
        val FEATURE =
            PlayerComparisonFeature(
                PlayerRankContext("GOLD", "I", Instant.parse("2026-09-18T00:00:00Z")),
                listOf(
                    PlayerCohortComparison(
                        scope = BenchmarkScope.POSITION,
                        position = "MIDDLE",
                        championId = null,
                        userGames = 5,
                        status = PlayerCohortComparisonStatus.AVAILABLE,
                        benchmarkCohort = BenchmarkCohort.position("KR", 420, "GOLD", "I", "MIDDLE"),
                        benchmarkSampleCount = 30,
                        benchmarkUniquePlayerCount = 10,
                        metrics = metrics(),
                    ),
                ),
            )
        val INPUT =
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
                            metrics = listOf(metric()),
                        ),
                    ),
                analysisLimitations = listOf(PlayerAnalysisLimitation("MATCH_LEVEL_BENCHMARK", "match-level")),
            )
        val COMMAND =
            AnalysisJobCommand(
                jobId = UUID.fromString("9b5a244e-f2f7-4a6a-b104-0a74a4d2e451"),
                gameName = GAME_NAME,
                tagLine = TAG_LINE,
                start = 0,
                count = 20,
                dedupeKey =
                    AnalysisJobDedupeKey.of(
                        AnalysisRateLimitKey("analysis-generation:client-a"),
                        GAME_NAME,
                        TAG_LINE,
                        0,
                        20,
                    ),
            )

        fun metrics(): PlayerComparisonMetrics =
            PlayerComparisonMetrics(
                metricComparison(),
                metricComparison(),
                metricComparison(),
                metricComparison(),
                metricComparison(),
                metricComparison(),
                metricComparison(),
            )

        fun metricComparison(): MetricComparison = MetricComparison(7.2, 6.7, 6.8, 0.5, 0.4, 6.3, 7.0, 7.4)

        fun metric(): PlayerAnalysisMetric = PlayerAnalysisMetric(PlayerAnalysisMetricName.KDA, 7.2, 6.7, 6.8, 0.5, 0.4, 6.3, 7.0, 7.4)
    }
}
