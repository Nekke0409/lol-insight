package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.benchmark.application.PeerBenchmarkQueryService
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkAvailability
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohort
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.benchmark.domain.PeerBenchmarkResult
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextBuilder
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonFeatureService
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryLoader
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistory
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import io.github.nekke0409.lolinsight.rank.application.PlayerRankLookupService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentQuestionServiceIntegrationTest {
    @Test
    fun `connects the scripted loop to actual Ranked Solo statistics and insufficient Emerald comparison`() {
        val historyLoader = mock(PlayerMatchHistoryLoader::class.java)
        val rankLookup = mock(PlayerRankLookupService::class.java)
        val benchmarkQuery = mock(PeerBenchmarkQueryService::class.java)
        val contextService =
            PlayerComparisonContextService(
                historyLoader,
                rankLookup,
                PlayerComparisonContextBuilder(),
            )
        val featureService = PlayerComparisonFeatureService(contextService, benchmarkQuery)
        val dispatcher = AgentToolDispatcher(JsonMapper.builder().build(), contextService, featureService)
        val rateLimiter = mock(AnalysisGenerationRateLimiter::class.java)
        val model =
            ScriptedModelGateway(
                toolTurn("call-stats", GET_RANKED_STATS, "{\"groupBy\":\"CHAMPION_POSITION\"}"),
                toolTurn("call-peer", GET_PEER_COMPARISON, "{\"groupBy\":\"CHAMPION_POSITION\"}"),
                finalTurn("챔피언별 통계와 비교 데이터 부족을 정리했습니다."),
            )
        val window = BenchmarkQueryWindow(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"))

        `when`(historyLoader.loadRecentRankedSoloMatches("Hide on bush", "KR1", 0, 20))
            .thenReturn(recentRankedSoloHistory())
        `when`(rankLookup.findCurrentRankContext(TARGET_PUUID))
            .thenReturn(PlayerRankContext("EMERALD", "IV", Instant.parse("2026-09-20T00:00:00Z")))
        `when`(benchmarkQuery.currentWindow()).thenReturn(window)
        cohorts().forEach { cohort ->
            `when`(benchmarkQuery.findBenchmarkExcludingPlayer(cohort, TARGET_PUUID, window))
                .thenReturn(PeerBenchmarkResult(BenchmarkAvailability.INSUFFICIENT_SAMPLE, 3, 2, null))
        }

        val response =
            AgentQuestionService(
                AgentQuestionProperties(enabled = true),
                rateLimiter,
                model,
                dispatcher,
                DirectToolExecutionRunner,
            ).answer("Hide on bush", "KR1", "챔피언별 통계와 비교를 알려줘", AnalysisRateLimitKey("127.0.0.1"))

        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertEquals(2, response.usedTools.size)
        assertEquals(listOf(true, true, false), model.allowToolCalls)
        assertEquals(2, model.continuationOutputs.size)
        assertTrue(
            model.continuationOutputs[0]
                .single()
                .output
                .contains("\"championId\":103"),
        )
        assertTrue(
            model.continuationOutputs[0]
                .single()
                .output
                .contains("\"championId\":99"),
        )
        assertTrue(
            model.continuationOutputs[1]
                .single()
                .output
                .contains("BENCHMARK_INSUFFICIENT_SAMPLE"),
        )
        assertTrue(
            model.continuationOutputs[1]
                .single()
                .output
                .contains("\"tier\":\"EMERALD\""),
        )
        assertFalse(
            model.continuationOutputs
                .flatten()
                .joinToString()
                .contains(TARGET_PUUID),
        )
        assertFalse(
            model.continuationOutputs
                .flatten()
                .joinToString()
                .contains("Hide on bush"),
        )
        verify(historyLoader).loadRecentRankedSoloMatches("Hide on bush", "KR1", 0, 20)
    }

    private fun recentRankedSoloHistory(): PlayerRecentMatchHistory =
        PlayerRecentMatchHistory(
            player = PlayerRecentMatchHistoryPlayer(TARGET_PUUID, "Hide on bush", "KR1"),
            start = 0,
            requestedCount = 20,
            sourceMatchCount = 6,
            unavailableCount = 0,
            matches = (1..5).map { match("KR_ahri_$it", championId = 103) } + match("KR_galio", championId = 99),
        )

    private fun cohorts(): List<BenchmarkCohort> =
        listOf(
            BenchmarkCohort.position("KR", RankedSoloQueue.ID, "EMERALD", "IV", "MIDDLE"),
            BenchmarkCohort.championPosition("KR", RankedSoloQueue.ID, "EMERALD", "IV", "MIDDLE", 103),
            BenchmarkCohort.championPosition("KR", RankedSoloQueue.ID, "EMERALD", "IV", "MIDDLE", 99),
        )

    private fun match(
        matchId: String,
        championId: Int,
    ): Match =
        Match(
            matchId = matchId,
            queueId = RankedSoloQueue.ID,
            gameMode = "CLASSIC",
            gameVersion = "16.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-09-19T00:00:00Z"),
            endedAt = Instant.parse("2026-09-19T00:20:00Z"),
            duration = Duration.ofMinutes(20),
            participants =
                listOf(
                    MatchParticipant(
                        participantId = championId,
                        puuid = TARGET_PUUID,
                        riotId = RiotIdSnapshot("Hide on bush", "KR1"),
                        teamId = 100,
                        won = true,
                        position = "MIDDLE",
                        champion = MatchChampion(championId, "Champion-$championId", 18),
                        kills = 5,
                        deaths = 2,
                        assists = 5,
                        pentaKills = 0,
                        laneMinionKills = 160,
                        neutralMinionKills = 0,
                        goldEarned = 10_000,
                        championDamageDealt = 20_000,
                        damageTaken = 10_000,
                        vision = MatchVision(20, 0, 0),
                        turretKills = 0,
                        itemIdsBySlot = emptyList(),
                        summonerSpellIds = emptyList(),
                        perks = MatchPerks(0, 0, 0, emptyList()),
                        reportedChallenges = null,
                    ),
                ),
            teams = emptyList(),
        )

    private fun toolTurn(
        callId: String,
        name: String,
        arguments: String,
    ): AgentModelTurn =
        AgentModelTurn(
            text = null,
            toolCalls = listOf(AgentModelToolCall(callId, name, arguments)),
            continuation = TestContinuation,
        )

    private fun finalTurn(text: String): AgentModelTurn =
        AgentModelTurn(text = text, toolCalls = emptyList(), continuation = TestContinuation)

    private object DirectToolExecutionRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: Duration,
            action: () -> T,
        ): T = action()
    }

    private object TestContinuation : AgentModelContinuation

    private class ScriptedModelGateway(
        vararg turns: AgentModelTurn,
    ) : AgentModelGateway {
        private val turns = ArrayDeque(turns.toList())
        val allowToolCalls = mutableListOf<Boolean>()
        val continuationOutputs = mutableListOf<List<AgentModelToolOutput>>()

        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            this.allowToolCalls += allowToolCalls
            return turns.removeFirst()
        }

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            continuationOutputs += outputs
            this.allowToolCalls += allowToolCalls
            return turns.removeFirst()
        }
    }

    private companion object {
        const val TARGET_PUUID = "target-puuid"
    }
}
