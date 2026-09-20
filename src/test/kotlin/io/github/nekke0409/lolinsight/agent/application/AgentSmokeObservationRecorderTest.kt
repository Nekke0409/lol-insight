package io.github.nekke0409.lolinsight.agent.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentSmokeObservationRecorderTest {
    @Test
    fun `keeps only permitted stats tool fields and normalizes an arbitrary tool name`() {
        val observation =
            AgentToolDispatchResult(
                toolName = "untrusted-tool\nsecond-line",
                success = true,
                payload =
                    AgentRankedStatsToolResult(
                        status = "AVAILABLE",
                        scope = "POSITION",
                        sample = AgentSampleResult(requestedMatchCount = 20, analyzedMatchCount = 7),
                        statistics =
                            listOf(
                                AgentScopedStatisticsResult(
                                    scope = "POSITION",
                                    position = "MIDDLE",
                                    champion = null,
                                    games = 7,
                                    wins = 4,
                                    losses = 3,
                                    winRate = 4.0 / 7.0,
                                    averageKda = 3.2,
                                    averageCsPerMinute = 7.1,
                                    averageGoldPerMinute = 410.0,
                                    averageDamagePerMinute = 610.0,
                                    averageVisionPerMinute = 1.2,
                                    averageKillParticipation = 0.48,
                                    averageDamageShare = 0.24,
                                ),
                            ),
                        metricUnits = AgentMetricUnits(),
                        limitations = emptyList(),
                    ),
                limitations = emptyList(),
            ).toSmokeObservation()

        assertEquals("UNSUPPORTED_TOOL", observation.toolName)
        assertEquals("POSITION", observation.groupBy)
        assertEquals(20, observation.requestedMatchCount)
        assertEquals(7, observation.analyzedMatchCount)
        assertEquals(
            AgentSmokeStatisticsObservation("MIDDLE", 7, 4.0 / 7.0, 3.2, 7.1),
            observation.statistics.single(),
        )
        assertEquals(emptyList(), observation.comparisons)
        assertFalse(observation.toString().contains("untrusted-tool"))
        assertFalse(observation.toString().contains("champion"))
    }

    @Test
    fun `keeps only the permitted peer comparison fields`() {
        val observation =
            AgentToolDispatchResult(
                toolName = GET_PEER_COMPARISON,
                success = true,
                payload =
                    AgentPeerComparisonToolResult(
                        status = "AVAILABLE",
                        requestedScope = "POSITION",
                        playerRank = AgentPlayerRankResult("GOLD", "I"),
                        comparisons =
                            listOf(
                                AgentPeerComparisonResult(
                                    scope = "POSITION",
                                    position = "TOP",
                                    champion = AgentChampionReference(86),
                                    userGames = 8,
                                    status = "AVAILABLE",
                                    benchmark = AgentBenchmarkScopeResult("GOLD", "I", "TOP", 30, 12),
                                    metrics = metrics(),
                                ),
                            ),
                        metricUnits = AgentMetricUnits(),
                        limitations = emptyList(),
                    ),
                limitations = emptyList(),
            ).toSmokeObservation()

        assertEquals("POSITION", observation.groupBy)
        assertEquals(emptyList(), observation.statistics)
        assertEquals(
            AgentSmokeComparisonObservation(
                scope = "POSITION",
                position = "TOP",
                userGames = 8,
                status = "AVAILABLE",
                tier = "GOLD",
                division = "I",
                benchmarkSampleCount = 30,
                benchmarkUniquePlayerCount = 12,
                kda = AgentSmokeMetricComparisonObservation(3.5, 2.7, 0.8),
                csPerMinute = AgentSmokeMetricComparisonObservation(7.3, 6.8, 0.5),
            ),
            observation.comparisons.single(),
        )
        assertFalse(observation.toString().contains("champion"))
        assertFalse(observation.toString().contains("86"))
    }

    private fun metrics(): AgentComparisonMetricsResult =
        AgentComparisonMetricsResult(
            kda = metric(3.5, 2.7, 0.8),
            csPerMinute = metric(7.3, 6.8, 0.5),
            goldPerMinute = metric(420.0, 400.0, 20.0),
            damagePerMinute = metric(700.0, 650.0, 50.0),
            visionPerMinute = metric(1.2, 1.1, 0.1),
            killParticipation = metric(0.5, 0.48, 0.02),
            damageShare = metric(0.25, 0.24, 0.01),
        )

    private fun metric(
        playerValue: Double,
        benchmarkMean: Double,
        differenceFromMean: Double,
    ): AgentMetricComparisonResult =
        AgentMetricComparisonResult(
            playerValue = playerValue,
            benchmarkMean = benchmarkMean,
            benchmarkMedian = benchmarkMean,
            differenceFromMean = differenceFromMean,
            differenceFromMedian = differenceFromMean,
            benchmarkP25 = benchmarkMean,
            benchmarkP75 = benchmarkMean,
            benchmarkP90 = benchmarkMean,
        )
}
