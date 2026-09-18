package io.github.nekke0409.lolinsight.player.web

import io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponse
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResponseStatus
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCapacityExceededException
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobCreated
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobDedupeKey
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobService
import io.github.nekke0409.lolinsight.analysis.job.application.AnalysisJobStatus
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimitExceededException
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import io.github.nekke0409.lolinsight.player.application.MatchParticipantSummaryResponse
import io.github.nekke0409.lolinsight.player.application.MatchSummaryResponse
import io.github.nekke0409.lolinsight.player.application.PlayerMatchHistoryService
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatistics
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsPlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsResponse
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsSampleResponse
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsService
import io.github.nekke0409.lolinsight.player.application.PlayerNotFoundException
import io.github.nekke0409.lolinsight.player.application.PlayerResponse
import io.github.nekke0409.lolinsight.player.application.PlayerService
import io.github.nekke0409.lolinsight.player.application.RecentMatchesPageResponse
import io.github.nekke0409.lolinsight.player.application.RecentMatchesPlayerResponse
import io.github.nekke0409.lolinsight.player.application.RecentMatchesResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PlayerControllerTest {
    private val playerService = mock(PlayerService::class.java)
    private val playerMatchHistoryService = mock(PlayerMatchHistoryService::class.java)
    private val playerMatchStatisticsService = mock(PlayerMatchStatisticsService::class.java)
    private val playerAnalysisService = mock(PlayerAnalysisService::class.java)
    private val analysisJobService = mock(AnalysisJobService::class.java)
    private val analysisGenerationRateLimiter = mock(AnalysisGenerationRateLimiter::class.java)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                PlayerController(
                    playerService,
                    playerMatchHistoryService,
                    playerMatchStatisticsService,
                    playerAnalysisService,
                    analysisJobService,
                    AnalysisRateLimitKeyResolver(),
                    analysisGenerationRateLimiter,
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `returns player lookup response`() {
        `when`(playerService.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(
                PlayerResponse(
                    puuid = "test-puuid",
                    gameName = "Hide on bush",
                    tagLine = "KR1",
                ),
            )

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.puuid").value("test-puuid"))
            .andExpect(jsonPath("$.gameName").value("Hide on bush"))
            .andExpect(jsonPath("$.tagLine").value("KR1"))
    }

    @Test
    fun `returns not found when Player lookup does not find an Account`() {
        `when`(playerService.findByRiotId("unknown", "KR1")).thenThrow(PlayerNotFoundException())

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}", "unknown", "KR1"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("Player not found."))
    }

    @Test
    fun `returns a recent Match summary list for a Player`() {
        `when`(playerMatchHistoryService.findRecentMatches("Hide on bush", "KR1", 0, 20))
            .thenReturn(recentMatchesResponse())

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.player.gameName").value("Hide on bush"))
            .andExpect(jsonPath("$.player.tagLine").value("KR1"))
            .andExpect(jsonPath("$.page.start").value(0))
            .andExpect(jsonPath("$.page.requestedCount").value(20))
            .andExpect(jsonPath("$.page.sourceMatchCount").value(1))
            .andExpect(jsonPath("$.page.returnedCount").value(1))
            .andExpect(jsonPath("$.page.partial").value(false))
            .andExpect(jsonPath("$.page.unavailableCount").value(0))
            .andExpect(jsonPath("$.matches[0].matchId").value("KR_1234567890"))
            .andExpect(jsonPath("$.matches[0].participant.championName").value("Aatrox"))
            .andExpect(jsonPath("$.matches[0].participant.totalCs").value(192))
            .andExpect(jsonPath("$.matches[0].participants").doesNotExist())
            .andExpect(jsonPath("$.matches[0].teams").doesNotExist())
    }

    @Test
    fun `rejects invalid recent Match pagination parameters`() {
        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1")
                    .param("count", "0"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1")
                    .param("count", "21"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1")
                    .param("start", "-1"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `returns aggregate Match statistics without Match or Riot DTO data`() {
        `when`(playerMatchStatisticsService.findStatistics("Hide on bush", "KR1", 0, 20))
            .thenReturn(playerMatchStatisticsResponse())

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}/stats", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.player.gameName").value("Hide on bush"))
            .andExpect(jsonPath("$.player.tagLine").value("KR1"))
            .andExpect(jsonPath("$.sample.start").value(0))
            .andExpect(jsonPath("$.sample.requestedCount").value(20))
            .andExpect(jsonPath("$.sample.analyzedCount").value(2))
            .andExpect(jsonPath("$.statistics.games").value(2))
            .andExpect(jsonPath("$.statistics.winRate").value(0.5))
            .andExpect(jsonPath("$.statistics.averageCsPerMinute").value(7.5))
            .andExpect(jsonPath("$.matches").doesNotExist())
            .andExpect(jsonPath("$.puuid").doesNotExist())
    }

    @Test
    fun `rejects invalid Match statistics pagination parameters`() {
        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/stats", "Hide on bush", "KR1")
                    .param("count", "0"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/stats", "Hide on bush", "KR1")
                    .param("count", "21"),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                get("/api/v1/players/{gameName}/{tagLine}/stats", "Hide on bush", "KR1")
                    .param("start", "-1"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `returns structured Korean AI analysis through the analysis endpoint`() {
        `when`(playerAnalysisService.analyze("Hide on bush", "KR1", 0, 20))
            .thenReturn(
                PlayerAnalysisResponse(
                    PlayerAnalysisResponseStatus.ANALYZED,
                    PlayerAnalysisResult(
                        summary = "Ahri MID 비교 결과입니다.",
                        observations = listOf(AnalysisInsight("CS/min 비교", "중앙값보다 높습니다.", "player=7.2")),
                        strengths = emptyList(),
                        focusAreas = emptyList(),
                        caveats = listOf("Benchmark v0.1은 match-level 관측치입니다."),
                    ),
                ),
            )

        mockMvc
            .perform(post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ANALYZED"))
            .andExpect(jsonPath("$.analysis.summary").value("Ahri MID 비교 결과입니다."))
            .andExpect(jsonPath("$.analysis.observations[0].evidence").value("player=7.2"))
    }

    @Test
    fun `returns deterministic unavailable analysis without a result`() {
        `when`(playerAnalysisService.analyze("Hide on bush", "KR1", 0, 20))
            .thenReturn(PlayerAnalysisResponse(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, null))

        mockMvc
            .perform(post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("INSUFFICIENT_COMPARISON_DATA"))
            .andExpect(jsonPath("$.analysis").doesNotExist())
    }

    @Test
    fun `rejects invalid analysis pagination parameters`() {
        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1")
                    .param("count", "21"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `accepts an asynchronous analysis job and returns its polling location`() {
        val jobId = UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf")
        `when`(analysisJobService.create("Hide on bush", "KR1", 0, 20, dedupeKey(DEFAULT_CLIENT_IP)))
            .thenReturn(AnalysisJobCreated(jobId, AnalysisJobStatus.PENDING, Instant.parse("2026-09-16T10:00:00Z")))

        mockMvc
            .perform(post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1"))
            .andExpect(status().isAccepted)
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/analysis-jobs/$jobId"))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.createdAt").value("2026-09-16T10:00:00Z"))
    }

    @Test
    fun `does not return accepted when analysis job capacity is exhausted`() {
        `when`(analysisJobService.create("Hide on bush", "KR1", 0, 20, dedupeKey(DEFAULT_CLIENT_IP)))
            .thenThrow(AnalysisJobCapacityExceededException())

        mockMvc
            .perform(post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.detail").value("AI analysis is temporarily at capacity."))
    }

    @Test
    fun `rejects a rate-limited analysis request before either generation flow starts`() {
        val clientIp = "203.0.113.10"
        doThrow(AnalysisGenerationRateLimitExceededException(42))
            .`when`(analysisGenerationRateLimiter)
            .check(AnalysisRateLimitKey("analysis-generation:$clientIp"))

        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
            .andExpect(jsonPath("$.code").value("ANALYSIS_RATE_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.detail").value("AI analysis generation rate limit exceeded."))

        verifyNoInteractions(playerAnalysisService, analysisJobService)
    }

    @Test
    fun `rejects a rate-limited asynchronous request before creating a job`() {
        val clientIp = "203.0.113.10"
        doThrow(AnalysisGenerationRateLimitExceededException(42))
            .`when`(analysisGenerationRateLimiter)
            .check(AnalysisRateLimitKey("analysis-generation:$clientIp"))

        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
            .andExpect(jsonPath("$.code").value("ANALYSIS_RATE_LIMIT_EXCEEDED"))

        verifyNoInteractions(analysisJobService)
    }

    @Test
    fun `shares one generation quota between asynchronous and synchronous requests`() {
        val clientIp = "203.0.113.10"
        val sharedRateLimiter =
            AnalysisGenerationRateLimiter(
                AnalysisRateLimitProperties(),
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC),
            )
        val sharedQuotaMockMvcBuilder =
            MockMvcBuilders.standaloneSetup(
                PlayerController(
                    playerService,
                    playerMatchHistoryService,
                    playerMatchStatisticsService,
                    playerAnalysisService,
                    analysisJobService,
                    AnalysisRateLimitKeyResolver(),
                    sharedRateLimiter,
                ),
            )
        sharedQuotaMockMvcBuilder.setControllerAdvice(GlobalExceptionHandler())
        val sharedQuotaMockMvc = sharedQuotaMockMvcBuilder.build()
        val created = AnalysisJobCreated(UUID.randomUUID(), AnalysisJobStatus.PENDING, Instant.parse("2026-09-17T00:00:00Z"))
        `when`(analysisJobService.create("Hide on bush", "KR1", 0, 20, dedupeKey(clientIp))).thenReturn(created)
        `when`(playerAnalysisService.analyze("Hide on bush", "KR1", 0, 20))
            .thenReturn(PlayerAnalysisResponse(PlayerAnalysisResponseStatus.INSUFFICIENT_COMPARISON_DATA, null))

        sharedQuotaMockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isAccepted)
        sharedQuotaMockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isOk)
        sharedQuotaMockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isAccepted)
        sharedQuotaMockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1")
                    .with { request ->
                        request.remoteAddr = clientIp
                        request
                    },
            ).andExpect(status().isTooManyRequests)

        verify(analysisJobService, times(2)).create("Hide on bush", "KR1", 0, 20, dedupeKey(clientIp))
        verify(playerAnalysisService).analyze("Hide on bush", "KR1", 0, 20)
    }

    @Test
    fun `rejects invalid asynchronous analysis job pagination parameters`() {
        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/analysis-jobs", "Hide on bush", "KR1")
                    .param("count", "21"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `maps AI provider failure without exposing provider detail`() {
        `when`(playerAnalysisService.analyze("Hide on bush", "KR1", 0, 20))
            .thenThrow(PlayerAnalysisProviderException(IllegalStateException("provider raw body")))

        mockMvc
            .perform(post("/api/v1/players/{gameName}/{tagLine}/analysis", "Hide on bush", "KR1"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.detail").value("Unable to generate AI analysis."))
    }

    @Test
    fun `preserves Riot rate limit response for recent Match lookups`() {
        `when`(playerMatchHistoryService.findRecentMatches("Hide on bush", "KR1", 0, 20))
            .thenThrow(
                RiotApiResponseException(
                    statusCode = HttpStatus.TOO_MANY_REQUESTS,
                    responseBody = "upstream-response-body",
                    retryAfterSeconds = 3,
                ),
            )

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
            .andExpect(jsonPath("$.detail").value("Unable to retrieve data from Riot Games."))
    }

    @Test
    fun `returns bad gateway when recent Match detail retrieval receives a Riot 5xx`() {
        `when`(playerMatchHistoryService.findRecentMatches("Hide on bush", "KR1", 0, 20))
            .thenThrow(RiotApiResponseException(HttpStatus.INTERNAL_SERVER_ERROR, "upstream-response-body"))

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.detail").value("Unable to retrieve data from Riot Games."))
    }

    @Test
    fun `returns service unavailable when recent Match detail retrieval has a transport failure`() {
        `when`(playerMatchHistoryService.findRecentMatches("Hide on bush", "KR1", 0, 20))
            .thenThrow(RiotApiTransportException(RestClientException("connect timed out")))

        mockMvc
            .perform(get("/api/v1/players/{gameName}/{tagLine}/matches", "Hide on bush", "KR1"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.detail").value("Riot Games is temporarily unavailable."))
    }

    private fun recentMatchesResponse(): RecentMatchesResponse =
        RecentMatchesResponse(
            player = RecentMatchesPlayerResponse(gameName = "Hide on bush", tagLine = "KR1"),
            page =
                RecentMatchesPageResponse(
                    start = 0,
                    requestedCount = 20,
                    sourceMatchCount = 1,
                    returnedCount = 1,
                    partial = false,
                    unavailableCount = 0,
                ),
            matches =
                listOf(
                    MatchSummaryResponse(
                        matchId = "KR_1234567890",
                        queueId = 420,
                        gameMode = "CLASSIC",
                        startedAt = Instant.parse("2026-01-01T12:00:00Z"),
                        durationSeconds = 1_800,
                        participant =
                            MatchParticipantSummaryResponse(
                                championId = 266,
                                championName = "Aatrox",
                                position = "TOP",
                                win = true,
                                kills = 8,
                                deaths = 2,
                                assists = 5,
                                totalCs = 192,
                                itemIds = listOf(3_073, 0, 3_364),
                            ),
                    ),
                ),
        )

    private fun playerMatchStatisticsResponse(): PlayerMatchStatisticsResponse =
        PlayerMatchStatisticsResponse(
            player = PlayerMatchStatisticsPlayerResponse(gameName = "Hide on bush", tagLine = "KR1"),
            sample = PlayerMatchStatisticsSampleResponse(start = 0, requestedCount = 20, analyzedCount = 2),
            statistics =
                PlayerMatchStatistics(
                    games = 2,
                    wins = 1,
                    losses = 1,
                    winRate = 0.5,
                    averageKills = 5.0,
                    averageDeaths = 3.0,
                    averageAssists = 7.0,
                    averageKda = 4.0,
                    averageCsPerMinute = 7.5,
                    averageGoldPerMinute = 400.0,
                    averageDamagePerMinute = 900.0,
                    averageVisionPerMinute = 1.2,
                    averageKillParticipation = 0.6,
                    averageDamageShare = 0.3,
                ),
        )

    private fun dedupeKey(clientIp: String): AnalysisJobDedupeKey =
        AnalysisJobDedupeKey.of(
            AnalysisRateLimitKey("analysis-generation:$clientIp"),
            "Hide on bush",
            "KR1",
            0,
            20,
        )

    private companion object {
        const val DEFAULT_CLIENT_IP = "127.0.0.1"
    }
}
