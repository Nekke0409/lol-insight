package io.github.nekke0409.lolinsight.player.web

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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClientException
import java.time.Instant

class PlayerControllerTest {
    private val playerService = mock(PlayerService::class.java)
    private val playerMatchHistoryService = mock(PlayerMatchHistoryService::class.java)
    private val playerMatchStatisticsService = mock(PlayerMatchStatisticsService::class.java)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(PlayerController(playerService, playerMatchHistoryService, playerMatchStatisticsService))
            .setControllerAdvice(GlobalExceptionHandler())
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
}
