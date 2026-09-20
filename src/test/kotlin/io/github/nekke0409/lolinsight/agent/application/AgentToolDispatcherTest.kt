package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkScope
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparison
import io.github.nekke0409.lolinsight.comparison.application.PlayerCohortComparisonStatus
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContext
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextPlayer
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextSample
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeature
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.comparison.application.PlayerPositionStatistics
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolDispatcherTest {
    private val contextService = mock(PlayerComparisonContextService::class.java)
    private val featureService = mock(PlayerComparisonFeatureService::class.java)
    private val dispatcher = AgentToolDispatcher(JsonMapper.builder().build(), contextService, featureService)

    @Test
    fun `returns only deterministic Ranked Solo statistics and reuses the context for a request`() {
        val context = context()
        `when`(contextService.buildContext("Hide on bush", "KR1", 0, 20)).thenReturn(context)
        val executionContext = dispatcher.newContext("Hide on bush", "KR1")

        val first = dispatcher.dispatch(call("get_ranked_stats", "{\"groupBy\":\"POSITION\"}"), executionContext)
        val second = dispatcher.dispatch(call("get_ranked_stats", "{\"groupBy\":\"POSITION\"}"), executionContext)
        val payload = first.payload as AgentRankedStatsToolResult
        val serialized = dispatcher.serialize(first)

        assertTrue(first.success)
        assertTrue(second.success)
        assertEquals(20, payload.sample.requestedMatchCount)
        assertEquals(7, payload.sample.analyzedMatchCount)
        assertEquals("POSITION", payload.statistics.single().scope)
        assertEquals("MID", payload.statistics.single().position)
        assertFalse(serialized.contains("target-puuid"))
        assertFalse(serialized.contains("Hide on bush"))
        verify(contextService).buildContext("Hide on bush", "KR1", 0, 20)
    }

    @Test
    fun `returns an exact structured insufficient peer comparison without substitutes`() {
        val context = context()
        `when`(contextService.buildContext("Hide on bush", "KR1", 0, 20)).thenReturn(context)
        `when`(featureService.buildFeature(context))
            .thenReturn(
                PlayerComparisonFeature(
                    rankContext = PlayerRankContext("EMERALD", "IV", Instant.parse("2026-09-20T00:00:00Z")),
                    comparisons =
                        listOf(
                            PlayerCohortComparison(
                                scope = BenchmarkScope.CHAMPION_POSITION,
                                position = "MID",
                                championId = 103,
                                userGames = 7,
                                status = PlayerCohortComparisonStatus.BENCHMARK_INSUFFICIENT_SAMPLE,
                                benchmarkCohort =
                                    BenchmarkCohort.championPosition(
                                        region = "KR",
                                        queueId = 420,
                                        tier = "EMERALD",
                                        division = "IV",
                                        position = "MID",
                                        championId = 103,
                                    ),
                                benchmarkSampleCount = 3,
                                benchmarkUniquePlayerCount = 2,
                                metrics = null,
                            ),
                        ),
                ),
            )

        val result =
            dispatcher.dispatch(
                call("get_peer_comparison", "{\"groupBy\":\"CHAMPION_POSITION\"}"),
                dispatcher.newContext("Hide on bush", "KR1"),
            )
        val payload = result.payload as AgentPeerComparisonToolResult
        val serialized = dispatcher.serialize(result)

        assertTrue(result.success)
        assertEquals("CHAMPION_POSITION", payload.requestedScope)
        assertEquals("EMERALD", payload.playerRank?.tier)
        assertEquals("BENCHMARK_INSUFFICIENT_SAMPLE", payload.comparisons.single().status)
        assertEquals(null, payload.comparisons.single().metrics)
        assertFalse(serialized.contains("103"))
        assertTrue(payload.limitations.single().contains("충분하지"))
    }

    @Test
    fun `rejects unknown fields invalid enums and unsupported tools`() {
        val executionContext = dispatcher.newContext("Hide on bush", "KR1")

        val invalidJson = dispatcher.dispatch(call("get_ranked_stats", "{not-json}"), executionContext)
        val invalidEnum = dispatcher.dispatch(call("get_ranked_stats", "{\"groupBy\":\"ALL\"}"), executionContext)
        val targetOverride =
            dispatcher.dispatch(
                call("get_ranked_stats", "{\"groupBy\":\"POSITION\",\"gameName\":\"other\"}"),
                executionContext,
            )
        val unknownTool = dispatcher.dispatch(call("read_database", "{\"groupBy\":\"POSITION\"}"), executionContext)

        assertFalse(invalidJson.success)
        assertFalse(invalidEnum.success)
        assertFalse(targetOverride.success)
        assertFalse(unknownTool.success)
        assertEquals("UNSUPPORTED_TOOL", (unknownTool.payload as AgentToolErrorResult).code)
    }

    @Test
    fun `normalizes valid tool arguments for repeated call detection`() {
        val compact = dispatcher.callSignature(call("get_ranked_stats", "{\"groupBy\":\"POSITION\"}"))
        val spaced = dispatcher.callSignature(call("get_ranked_stats", "{ \"groupBy\" : \"POSITION\" }"))

        assertEquals(compact, spaced)
    }

    private fun context(): PlayerComparisonContext =
        PlayerComparisonContext(
            player = PlayerComparisonContextPlayer("Hide on bush", "KR1"),
            targetPuuid = "target-puuid",
            rankContext = null,
            sample = PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 7),
            positionStatistics =
                listOf(
                    PlayerPositionStatistics(
                        position = "MID",
                        games = 7,
                        wins = 4,
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
            championPositionStatistics = emptyList(),
        )

    private fun call(
        name: String,
        arguments: String,
    ): AgentModelToolCall = AgentModelToolCall("call-1", name, arguments)
}
