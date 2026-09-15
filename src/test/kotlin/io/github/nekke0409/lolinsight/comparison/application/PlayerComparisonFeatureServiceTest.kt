package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkMetricDistribution
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
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
    fun `returns separate unranked comparisons for each scope without querying benchmarks`() {
        stubContext(rankContext = null)

        val comparisons = service.buildFeature(GAME_NAME, TAG_LINE, 0, 20).comparisons

        assertEquals(listOf(BenchmarkScope.POSITION, BenchmarkScope.CHAMPION_POSITION), comparisons.map { it.scope })
        assertEquals(listOf(null, 103), comparisons.map { it.championId })
        assertEquals(listOf(PlayerCohortComparisonStatus.UNRANKED, PlayerCohortComparisonStatus.UNRANKED), comparisons.map { it.status })
        assertTrueNoBenchmark(comparisons)
        verifyNoInteractions(peerBenchmarkQueryService)
    }

    @Test
    fun `joins each user statistical unit only to its matching benchmark scope without fallback`() {
        val positionStatistics = positionStats(games = 6, averageKda = 4.0)
        val championStatistics = championPositionStats(games = 3, averageKda = 8.0)
        val positionCohort = positionCohort(positionStatistics)
        val championCohort = championPositionCohort(championStatistics)
        stubContext(positionStatistics = listOf(positionStatistics), championPositionStatistics = listOf(championStatistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(positionCohort, TARGET_PUUID))
            .thenReturn(availableResult(positionCohort))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(championCohort, TARGET_PUUID))
            .thenReturn(PeerBenchmarkResult(BenchmarkAvailability.INSUFFICIENT_SAMPLE, 23, 5, null))

        val comparisons = service.buildFeature(GAME_NAME, TAG_LINE, 0, 20).comparisons

        val positionComparison = comparisons.single { it.scope == BenchmarkScope.POSITION }
        assertEquals(PlayerCohortComparisonStatus.AVAILABLE, positionComparison.status)
        assertEquals(null, positionComparison.championId)
        assertEquals(4.0, assertNotNull(positionComparison.metrics).kda.playerValue)

        val championComparison = comparisons.single { it.scope == BenchmarkScope.CHAMPION_POSITION }
        assertEquals(PlayerCohortComparisonStatus.INSUFFICIENT_USER_SAMPLE, championComparison.status)
        assertEquals(103, championComparison.championId)
        assertEquals(23L, championComparison.benchmarkSampleCount)
        assertEquals(5L, championComparison.benchmarkUniquePlayerCount)
        assertNull(championComparison.metrics)

        verify(peerBenchmarkQueryService).findBenchmarkExcludingPlayer(positionCohort, TARGET_PUUID)
        verify(peerBenchmarkQueryService).findBenchmarkExcludingPlayer(championCohort, TARGET_PUUID)
        verifyNoMoreInteractions(peerBenchmarkQueryService)
    }

    @Test
    fun `uses each scope's own user averages for available metric comparisons`() {
        val positionStatistics = positionStats(games = 6, averageKda = 4.0)
        val championStatistics = championPositionStats(games = 5, averageKda = 8.0)
        val positionCohort = positionCohort(positionStatistics)
        val championCohort = championPositionCohort(championStatistics)
        stubContext(positionStatistics = listOf(positionStatistics), championPositionStatistics = listOf(championStatistics))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(positionCohort, TARGET_PUUID))
            .thenReturn(availableResult(positionCohort))
        `when`(peerBenchmarkQueryService.findBenchmarkExcludingPlayer(championCohort, TARGET_PUUID))
            .thenReturn(availableResult(championCohort))

        val comparisons = service.buildFeature(GAME_NAME, TAG_LINE, 0, 20).comparisons

        assertEquals(4.0, assertNotNull(comparisons[0].metrics).kda.playerValue)
        assertEquals(8.0, assertNotNull(comparisons[1].metrics).kda.playerValue)
    }

    private fun stubContext(
        rankContext: PlayerRankContext? = RANK_CONTEXT,
        positionStatistics: List<PlayerPositionStatistics> = listOf(positionStats()),
        championPositionStatistics: List<PlayerChampionPositionStatistics> = listOf(championPositionStats()),
    ) {
        `when`(playerComparisonContextService.buildContext(GAME_NAME, TAG_LINE, 0, 20))
            .thenReturn(
                PlayerComparisonContext(
                    player = PlayerComparisonContextPlayer(GAME_NAME, TAG_LINE),
                    targetPuuid = TARGET_PUUID,
                    rankContext = rankContext,
                    sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 8),
                    positionStatistics = positionStatistics,
                    championPositionStatistics = championPositionStatistics,
                ),
            )
    }

    private fun positionStats(
        games: Int = 5,
        averageKda: Double = 7.2,
    ): PlayerPositionStatistics =
        PlayerPositionStatistics(
            position = "MIDDLE",
            games = games,
            wins = games - 1,
            winRate = (games - 1).toDouble() / games,
            averageKda = averageKda,
            averageCsPerMinute = 7.2,
            averageGoldPerMinute = 7.2,
            averageDamagePerMinute = 7.2,
            averageVisionPerMinute = 7.2,
            averageKillParticipation = 0.72,
            averageDamageShare = 0.72,
        )

    private fun championPositionStats(
        games: Int = 5,
        averageKda: Double = 7.2,
    ): PlayerChampionPositionStatistics =
        PlayerChampionPositionStatistics(
            championId = 103,
            position = "MIDDLE",
            games = games,
            wins = games - 1,
            winRate = (games - 1).toDouble() / games,
            averageKda = averageKda,
            averageCsPerMinute = 7.2,
            averageGoldPerMinute = 7.2,
            averageDamagePerMinute = 7.2,
            averageVisionPerMinute = 7.2,
            averageKillParticipation = 0.72,
            averageDamageShare = 0.72,
        )

    private fun positionCohort(statistics: PlayerPositionStatistics): BenchmarkCohort =
        BenchmarkCohort.position("KR", 420, "GOLD", "I", statistics.position)

    private fun championPositionCohort(statistics: PlayerChampionPositionStatistics): BenchmarkCohort =
        BenchmarkCohort.championPosition("KR", 420, "GOLD", "I", statistics.position, statistics.championId)

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

    private fun assertTrueNoBenchmark(comparisons: List<PlayerCohortComparison>) {
        comparisons.forEach { comparison ->
            assertNull(comparison.benchmarkCohort)
            assertEquals(0, comparison.benchmarkSampleCount)
            assertEquals(0, comparison.benchmarkUniquePlayerCount)
            assertNull(comparison.metrics)
        }
    }

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        const val TARGET_PUUID = "target-puuid"
        val RANK_CONTEXT = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))
    }
}
