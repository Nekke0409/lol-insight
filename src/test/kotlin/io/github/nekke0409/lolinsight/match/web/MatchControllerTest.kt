package io.github.nekke0409.lolinsight.match.web

import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import io.github.nekke0409.lolinsight.match.application.MatchObjectivesResponse
import io.github.nekke0409.lolinsight.match.application.MatchParticipantResponse
import io.github.nekke0409.lolinsight.match.application.MatchPerksResponse
import io.github.nekke0409.lolinsight.match.application.MatchResponse
import io.github.nekke0409.lolinsight.match.application.MatchRunePathResponse
import io.github.nekke0409.lolinsight.match.application.MatchService
import io.github.nekke0409.lolinsight.match.application.MatchTeamResponse
import io.github.nekke0409.lolinsight.match.application.MatchVisionResponse
import io.github.nekke0409.lolinsight.match.application.ObjectiveResultResponse
import io.github.nekke0409.lolinsight.match.application.RiotIdSnapshotResponse
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class MatchControllerTest {
    private val matchService = mock(MatchService::class.java)
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(MatchController(matchService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `returns a Match lookup response`() {
        `when`(matchService.findByMatchId("KR_1234567890")).thenReturn(matchResponse())

        mockMvc
            .perform(get("/api/v1/matches/{matchId}", "KR_1234567890"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.matchId").value("KR_1234567890"))
            .andExpect(jsonPath("$.queueId").value(420))
            .andExpect(jsonPath("$.durationSeconds").value(1_800))
            .andExpect(jsonPath("$.participants[0].championName").value("Aatrox"))
            .andExpect(jsonPath("$.participants[0].itemIds[0]").value(3_073))
            .andExpect(jsonPath("$.teams[0].objectives.tower.killCount").value(8))
            .andExpect(jsonPath("$.gameVersion").doesNotExist())
            .andExpect(jsonPath("$.participants[0].reportedChallenges").doesNotExist())
    }

    @Test
    fun `returns a generic error without Riot response details`() {
        `when`(matchService.findByMatchId("KR_1234567890"))
            .thenThrow(RiotApiResponseException(HttpStatus.NOT_FOUND, "upstream-secret-response-body"))

        mockMvc
            .perform(get("/api/v1/matches/{matchId}", "KR_1234567890"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.detail").value("Unable to retrieve data from Riot Games."))
            .andExpect(content().string(not(containsString("upstream-secret-response-body"))))
    }

    private fun matchResponse(): MatchResponse {
        val objective = ObjectiveResultResponse(wasFirst = false, killCount = 0)
        return MatchResponse(
            matchId = "KR_1234567890",
            queueId = 420,
            gameMode = "CLASSIC",
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            durationSeconds = 1_800,
            participants =
                listOf(
                    MatchParticipantResponse(
                        puuid = "test-puuid",
                        riotId = RiotIdSnapshotResponse("Hide on bush", "KR1"),
                        championId = 266,
                        championName = "Aatrox",
                        teamId = 100,
                        position = "TOP",
                        kills = 8,
                        deaths = 2,
                        assists = 5,
                        win = true,
                        laneMinionKills = 180,
                        neutralMinionKills = 12,
                        goldEarned = 12_345,
                        championDamageDealt = 24_680,
                        vision = MatchVisionResponse(score = 28, wardsPlaced = 9, wardsKilled = 3),
                        itemIds = listOf(3_073, 0, 3_364),
                        summonerSpellIds = listOf(4, 12),
                        perks =
                            MatchPerksResponse(
                                offenseStatPerkId = 5_008,
                                flexStatPerkId = 5_010,
                                defenseStatPerkId = 5_011,
                                runePaths = listOf(MatchRunePathResponse(8_010, listOf(8_005))),
                            ),
                    ),
                ),
            teams =
                listOf(
                    MatchTeamResponse(
                        teamId = 100,
                        win = true,
                        bannedChampionIds = listOf(238),
                        objectives =
                            MatchObjectivesResponse(
                                atakhan = objective,
                                baron = objective,
                                champion = objective,
                                dragon = objective,
                                horde = objective,
                                inhibitor = objective,
                                riftHerald = objective,
                                tower = ObjectiveResultResponse(wasFirst = true, killCount = 8),
                            ),
                    ),
                ),
        )
    }
}
