package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountClient
import io.github.nekke0409.lolinsight.player.infrastructure.riot.RiotAccountResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerMatchStatisticsServiceTest {
    private val riotAccountClient = mock(RiotAccountClient::class.java)
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val matchDetailExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_MATCH_DETAIL_REQUESTS)
    private val service =
        PlayerMatchStatisticsService(
            PlayerMatchHistoryLoader(riotAccountClient, riotMatchClient, matchDetailExecutor),
            PlayerMatchStatisticsCalculator(),
        )

    @AfterEach
    fun shutDownMatchDetailExecutor() {
        matchDetailExecutor.shutdownNow()
    }

    @Test
    fun `loads a Player Match sample and returns only aggregate statistics`() {
        `when`(riotAccountClient.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(RiotAccountResponse("target-puuid", "Hide on bush", "KR1"))
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 5, 2))
            .thenReturn(listOf("KR_target", "KR_without_target"))
        `when`(riotMatchClient.findMatchById("KR_target"))
            .thenReturn(
                match(
                    "KR_target",
                    participant(puuid = "target-puuid", teamId = 100, won = true, kills = 8, assists = 4),
                    participant(puuid = "ally-puuid", teamId = 100, kills = 2),
                    participant(puuid = "opponent-puuid", teamId = 200, kills = 50),
                ),
            )
        `when`(riotMatchClient.findMatchById("KR_without_target"))
            .thenReturn(match("KR_without_target", participant(puuid = "other-puuid", teamId = 200)))

        val response = service.findStatistics("Hide on bush", "KR1", 5, 2)

        verify(riotAccountClient).findByRiotId("Hide on bush", "KR1")
        verify(riotMatchClient).findMatchIdsByPuuid("target-puuid", 5, 2)
        verify(riotMatchClient).findMatchById("KR_target")
        verify(riotMatchClient).findMatchById("KR_without_target")
        assertEquals("Hide on bush", response.player.gameName)
        assertEquals("KR1", response.player.tagLine)
        assertEquals(5, response.sample.start)
        assertEquals(2, response.sample.requestedCount)
        assertEquals(1, response.sample.analyzedCount)
        assertEquals(1, response.statistics.games)
        assertEquals(1, response.statistics.wins)
        assertEquals(0, response.statistics.losses)
        assertEquals(1.0, response.statistics.winRate, 0.000001)
        assertEquals(1.2, response.statistics.averageKillParticipation, 0.000001)
        assertEquals(0.5, response.statistics.averageDamageShare, 0.000001)
        assertEquals(false, response.toString().contains("target-puuid"))
    }

    @Test
    fun `returns a valid empty statistics response when no Match IDs are found`() {
        `when`(riotAccountClient.findByRiotId("Hide on bush", "KR1"))
            .thenReturn(RiotAccountResponse("target-puuid", "Hide on bush", "KR1"))
        `when`(riotMatchClient.findMatchIdsByPuuid("target-puuid", 0, 20)).thenReturn(emptyList())

        val response = service.findStatistics("Hide on bush", "KR1", 0, 20)

        verify(riotAccountClient).findByRiotId("Hide on bush", "KR1")
        verify(riotMatchClient).findMatchIdsByPuuid("target-puuid", 0, 20)
        verifyNoMoreInteractions(riotMatchClient)
        assertEquals(0, response.sample.analyzedCount)
        assertEquals(PlayerMatchStatistics.empty(), response.statistics)
    }

    @Test
    fun `rejects invalid pagination before calling Riot`() {
        assertFailsWith<IllegalArgumentException> {
            service.findStatistics("Hide on bush", "KR1", start = -1, count = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            service.findStatistics("Hide on bush", "KR1", start = 0, count = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.findStatistics("Hide on bush", "KR1", start = 0, count = 21)
        }

        verifyNoInteractions(riotAccountClient, riotMatchClient)
    }

    private fun match(
        matchId: String,
        vararg participants: MatchParticipant,
    ): Match {
        val objective = ObjectiveResult(wasFirst = false, killCount = 0)
        return Match(
            matchId = matchId,
            queueId = 420,
            gameMode = "CLASSIC",
            gameVersion = "16.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            endedAt = Instant.parse("2026-01-01T12:30:00Z"),
            duration = Duration.ofMinutes(30),
            participants = participants.toList(),
            teams =
                listOf(
                    MatchTeam(
                        teamId = 100,
                        won = true,
                        bannedChampionIds = emptyList(),
                        objectives = matchObjectives(objective),
                    ),
                    MatchTeam(
                        teamId = 200,
                        won = false,
                        bannedChampionIds = emptyList(),
                        objectives = matchObjectives(objective),
                    ),
                ),
        )
    }

    private fun matchObjectives(objective: ObjectiveResult): MatchObjectives =
        MatchObjectives(
            atakhan = objective,
            baron = objective,
            champion = objective,
            dragon = objective,
            horde = objective,
            inhibitor = objective,
            riftHerald = objective,
            tower = objective,
        )

    private fun participant(
        puuid: String,
        teamId: Int,
        won: Boolean = teamId == 100,
        kills: Int = 0,
        deaths: Int = 0,
        assists: Int = 0,
        championDamageDealt: Int = 1_000,
    ): MatchParticipant =
        MatchParticipant(
            participantId = teamId,
            puuid = puuid,
            riotId = RiotIdSnapshot("Player", "KR1"),
            teamId = teamId,
            won = won,
            position = "TOP",
            champion = MatchChampion(266, "Aatrox", 18),
            kills = kills,
            deaths = deaths,
            assists = assists,
            pentaKills = 0,
            laneMinionKills = 0,
            neutralMinionKills = 0,
            goldEarned = 0,
            championDamageDealt = championDamageDealt,
            damageTaken = 0,
            vision = MatchVision(score = 0, wardsPlaced = 0, wardsKilled = 0),
            turretKills = 0,
            itemIdsBySlot = emptyList(),
            summonerSpellIds = emptyList(),
            perks = MatchPerks(0, 0, 0, emptyList()),
            reportedChallenges = null,
        )
}
