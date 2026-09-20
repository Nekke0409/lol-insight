package io.github.nekke0409.lolinsight.agent.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ExtendWith(OutputCaptureExtension::class)
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

    @Test
    fun `writes permitted AVAILABLE peer comparison details to the actual log`(output: CapturedOutput) {
        SafeLoggingAgentSmokeObservationRecorder().record(
            peerComparisonResult(
                comparison =
                    AgentPeerComparisonResult(
                        scope = "POSITION",
                        position = "TOP",
                        champion = AgentChampionReference(900001),
                        userGames = 8,
                        status = "AVAILABLE",
                        benchmark = AgentBenchmarkScopeResult("GOLD", "I", "TOP", 30, 12),
                        metrics = metrics(),
                    ),
            ),
        )

        val log = output.lastSmokeLogLine()

        assertContains(log, "tool=get_peer_comparison")
        assertContains(log, "success=true")
        assertContains(log, "groupBy=POSITION")
        assertContains(log, "comparisons=[AgentSmokeComparisonObservation(")
        assertContains(log, "scope=POSITION")
        assertContains(log, "position=TOP")
        assertContains(log, "userGames=8")
        assertContains(log, "status=AVAILABLE")
        assertContains(log, "tier=GOLD")
        assertContains(log, "division=I")
        assertContains(log, "benchmarkSampleCount=30")
        assertContains(log, "benchmarkUniquePlayerCount=12")
        assertContains(log, "kda=AgentSmokeMetricComparisonObservation(playerValue=3.5, benchmarkMean=2.7, differenceFromMean=0.8)")
        assertContains(log, "csPerMinute=AgentSmokeMetricComparisonObservation(playerValue=7.3, benchmarkMean=6.8, differenceFromMean=0.5)")
        assertFalse(log.contains("champion="))
        assertFalse(log.contains("900001"))
    }

    @Test
    fun `writes unavailable comparison status and sample without manufacturing metrics`(output: CapturedOutput) {
        SafeLoggingAgentSmokeObservationRecorder().record(
            peerComparisonResult(
                comparison =
                    AgentPeerComparisonResult(
                        scope = "POSITION",
                        position = "TOP",
                        champion = null,
                        userGames = 3,
                        status = "BENCHMARK_INSUFFICIENT_SAMPLE",
                        benchmark = AgentBenchmarkScopeResult("EMERALD", "IV", "TOP", 3, 2),
                        metrics = null,
                    ),
                tier = "EMERALD",
                division = "IV",
            ),
        )

        val log = output.lastSmokeLogLine()

        assertContains(log, "tool=get_peer_comparison")
        assertContains(log, "success=true")
        assertContains(log, "status=BENCHMARK_INSUFFICIENT_SAMPLE")
        assertContains(log, "benchmarkSampleCount=3")
        assertContains(log, "benchmarkUniquePlayerCount=2")
        assertContains(log, "kda=null")
        assertContains(log, "csPerMinute=null")
        assertFalse(log.contains("playerValue="))
        assertFalse(log.contains("benchmarkMean="))
        assertFalse(log.contains("differenceFromMean="))
    }

    @Test
    fun `normalizes arbitrary tool names and excludes raw payload values from the actual log`(output: CapturedOutput) {
        val playerIdentifier = "player-identifier-sentinel"
        val question = "question-sentinel"
        val finalAnswer = "final-answer-sentinel"
        val rawProviderResponse = "raw-provider-response-sentinel"
        val apiKey = "api-key-sentinel"

        SafeLoggingAgentSmokeObservationRecorder().record(
            AgentToolDispatchResult(
                toolName = "untrusted-tool\n$playerIdentifier",
                success = false,
                payload = AgentToolErrorResult("REJECTED", apiKey, "$question $finalAnswer $rawProviderResponse"),
                limitations = listOf(playerIdentifier, question, finalAnswer, rawProviderResponse, apiKey),
            ),
        )

        val log = output.lastSmokeLogLine()

        assertContains(log, "tool=UNSUPPORTED_TOOL")
        assertFalse(log.contains("untrusted-tool"))
        assertFalse(log.contains(playerIdentifier))
        assertFalse(log.contains(question))
        assertFalse(log.contains(finalAnswer))
        assertFalse(log.contains(rawProviderResponse))
        assertFalse(log.contains(apiKey))
    }

    private fun peerComparisonResult(
        comparison: AgentPeerComparisonResult,
        tier: String = "GOLD",
        division: String = "I",
    ): AgentToolDispatchResult =
        AgentToolDispatchResult(
            toolName = GET_PEER_COMPARISON,
            success = true,
            payload =
                AgentPeerComparisonToolResult(
                    status = "AVAILABLE",
                    requestedScope = "POSITION",
                    playerRank = AgentPlayerRankResult(tier, division),
                    comparisons = listOf(comparison),
                    metricUnits = AgentMetricUnits(),
                    limitations = emptyList(),
                ),
            limitations = emptyList(),
        )

    private fun CapturedOutput.lastSmokeLogLine(): String =
        all
            .lineSequence()
            .last { it.contains("agent_smoke_tool") }

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
