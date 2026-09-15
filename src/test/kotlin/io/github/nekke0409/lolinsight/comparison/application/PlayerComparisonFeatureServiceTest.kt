package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmark
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmarkResult
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerComparisonFeatureServiceTest {
    private val playerComparisonContextService = mock(PlayerComparisonContextService::class.java)
    private val peerBenchmarkQueryService = mock(PeerBenchmarkQueryService::class.java)
    private val service =
        PlayerComparisonFeatureService(
            playerComparisonContextService = playerComparisonContextService,
            peerBenchmarkQueryService = peerBenchmarkQueryService,
        )

    @Test
    fun `returns UNRANKED comparisons without querying benchmarks when the user has no Solo rank`() {
        val statistics = statistics(games = 5)
        stubContext(rankContext = null, cohortStatistics = listOf(statistics))

        val feature = service.buildFeature("Hide on bush", "KR1", 0, 20)

        assertEquals(null, feature.rankContext)
        assertEquals(PlayerCohortComparisonStatus.UNRANKED, feature.comparisons.single().status)
        assertEquals(0L, feature.comparisons.single().benchmarkSampleCount)
        assertEquals(0L, feature.comparisons.single().benchmarkUniquePlayerCount)
        assertNull(feature.comparisons.single().benchmarkCohort)
        assertNull(feature.comparisons.single().metrics)
        verifyNoInteractions(peerBenchmarkQueryService)
    }

    @Test
    fun `returns INSUFFICIENT_USER_SAMPLE before using an available benchmark for metric comparisons`() {
        val statistics = statistics(games = 4)
        val cohort = cohort(statistics)
        stubContext(cohortStatistics = listOf(statistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, TARGET_PUUID)).thenReturn(availableResult(cohort))

        val comparison = service.buildFeature("Hide on bush", "KR1", 0, 20).comparisons.single()

        assertEquals(PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE, comparison.status)
        assertEquals(30L, comparison.benchmarkSampleCount)
        assertEquals(10L, comparison.benchmarkUniquePlayerCount)
        assertNull(comparison.metrics)
        verify(peerBenchmarkQueryService).findBenchmarkExcludingPlayer(cohort, TARGET_PUUID)
    }

    @Test
    fun `maps no benchmark data to BENCHMARK_NO_DATA`() {
        val statistics = statistics(games = 5)
        val cohort = cohort(statistics)
        stubContext(cohortStatistics = listOf(statistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, TARGET_PUUID))
            .thenReturn(PeerBenchmarkResult(BenchmarkAvailability.NO_DATA, 0, 0, null))

        val comparison = service.buildFeature("Hide on bush", "KR1", 0, 20).comparisons.single()

        assertEquals(PlayerCohortComparisonStatus.BENCHMARK_NO_DATA, comparison.status)
        assertEquals(cohort, comparison.benchmarkCohort)
        assertNull(comparison.metrics)
    }

    @Test
    fun `maps insufficient benchmark samples to BENCHMARK_INSUFFICIENT_SAMPLE`() {
        val statistics = statistics(games = 5)
        val cohort = cohort(statistics)
        stubContext(cohortStatistics = listOf(statistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, TARGET_PUUID))
            .thenReturn(PeerBenchmarkResult(BenchmarkAvailability.INSUFFICIENT_SAMPLE, 4, 2, null))

        val comparison = service.buildFeature("Hide on bush", "KR1", 0, 20).comparisons.single()

        assertEquals(PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE, comparison.status)
        assertEquals(4L, comparison.benchmarkSampleCount)
        assertEquals(2L, comparison.benchmarkUniquePlayerCount)
        assertNull(comparison.metrics)
    }

    @Test
    fun `uses the exact cohort and maps every available metric with backend calculated differences`() {
        val statistics = statistics(games = 5)
        val expectedCohort = cohort(statistics)
        stubContext(cohortStatistics = listOf(statistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(expectedCohort, TARGET_PUUID))
            .thenReturn(availableResult(expectedCohort))

        val comparison = service.buildFeature("Hide on bush", "KR1", 0, 20).comparisons.single()

        assertEquals(PlayerCohortComparisonStatus.AVAILABLE, comparison.status)
        assertEquals(expectedCohort, comparison.benchmarkCohort)
        assertEquals(30L, comparison.benchmarkSampleCount)
        assertEquals(10L, comparison.benchmarkUniquePlayerCount)
        val metrics = assertNotNull(comparison.metrics)
        listOf(
            metrics.kda,
            metrics.csPerMinute,
            metrics.goldPerMinute,
            metrics.damagePerMinute,
            metrics.visionPerMinute,
            metrics.killParticipation,
            metrics.damageShare,
        ).forEach { metric ->
            assertEquals(7.2, metric.playerValue, TOLERANCE)
            assertEquals(6.7, metric.benchmarkMean, TOLERANCE)
            assertEquals(6.8, metric.benchmarkMedian, TOLERANCE)
            assertEquals(0.5, metric.differenceFromMean, TOLERANCE)
            assertEquals(0.4, metric.differenceFromMedian, TOLERANCE)
            assertEquals(6.3, metric.benchmarkP25, TOLERANCE)
            assertEquals(7.0, metric.benchmarkP75, TOLERANCE)
            assertEquals(7.4, metric.benchmarkP90, TOLERANCE)
        }
        verify(peerBenchmarkQueryService).findBenchmarkExcludingPlayer(expectedCohort, TARGET_PUUID)
        verifyNoMoreInteractions(peerBenchmarkQueryService)
    }

    @Test
    fun `orders comparisons by user games position and champion regardless of context input order`() {
        val middleAhri = statistics(championId = 103, position = "MIDDLE", games = 8)
        val middleGaren = statistics(championId = 86, position = "MIDDLE", games = 5)
        val topAhri = statistics(championId = 103, position = "TOP", games = 5)
        val statistics = listOf(topAhri, middleGaren, middleAhri)
        stubContext(cohortStatistics = statistics)
        statistics.forEach { statistics ->
            val cohort = cohort(statistics)
            `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(cohort, TARGET_PUUID)).thenReturn(availableResult(cohort))
        }

        val comparisons = service.buildFeature("Hide on bush", "KR1", 0, 20).comparisons

        assertEquals(
            listOf(
                middleAhri.championId to middleAhri.position,
                middleGaren.championId to middleGaren.position,
                topAhri.championId to topAhri.position,
            ),
            comparisons.map { it.championId to it.position },
        )
    }

    private fun stubContext(
        rankContext: PlayerRankContext? = RANK_CONTEXT,
        cohortStatistics: List<PlayerCohortStatistics>,
    ) {
        `when`(playerComparisonContextService.buildContext("Hide on bush", "KR1", 0, 20))
            .thenReturn(
                PlayerComparisonContext(
                    player = PlayerComparisonContextPlayer("Hide on bush", "KR1"),
                    targetPuuid = TARGET_PUUID,
                    rankContext = rankContext,
                    sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = cohortStatistics.sumOf { it.games }),
                    cohortStatistics = cohortStatistics,
                ),
            )
    }

    private fun statistics(
        championId: Int = 103,
        position: String = "MIDDLE",
        games: Int,
    ): PlayerCohortStatistics =
        PlayerCohortStatistics(
            championId = championId,
            position = position,
            games = games,
            wins = games - 1,
            winRate = (games - 1).toDouble() / games,
            averageKda = 7.2,
            averageCsPerMinute = 7.2,
            averageGoldPerMinute = 7.2,
            averageDamagePerMinute = 7.2,
            averageVisionPerMinute = 7.2,
            averageKillParticipation = 7.2,
            averageDamageShare = 7.2,
        )

    private fun cohort(statistics: PlayerCohortStatistics): BenchmarkCohort =
        BenchmarkCohort(
            region = "KR",
            queueId = 420,
            tier = "GOLD",
            division = "I",
            position = statistics.position,
            championId = statistics.championId,
        )

    private fun availableResult(cohort: BenchmarkCohort): PeerBenchmarkResult {
        val distribution = BenchmarkMetricDistribution(mean = 6.7, median = 6.8, p25 = 6.3, p75 = 7.0, p90 = 7.4)
        return PeerBenchmarkResult(
            status = BenchmarkAvailability.AVAILABLE,
            sampleCount = 30,
            uniquePlayerCount = 10,
            benchmark =
                PeerBenchmark(
                    cohort = cohort,
                    sampleCount = 30,
                    uniquePlayerCount = 10,
                    kda = distribution,
                    csPerMinute = distribution,
                    goldPerMinute = distribution,
                    damagePerMinute = distribution,
                    visionPerMinute = distribution,
                    killParticipation = distribution,
                    damageShare = distribution,
                ),
        )
    }

    private companion object {
        const val TARGET_PUUID = "target-puuid"
        val RANK_CONTEXT = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))
        const val TOLERANCE = 0.000001
    }
}
