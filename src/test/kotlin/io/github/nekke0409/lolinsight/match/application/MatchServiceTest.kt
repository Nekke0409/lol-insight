package io.github.nekke0409.lolinsight.match.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchRunePath
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.ReportedMatchChallenges
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MatchServiceTest {
    private val riotMatchClient = mock(RiotMatchClient::class.java)
    private val matchService = MatchService(riotMatchClient)

    @Test
    fun `finds a Match by ID and maps selected domain data to the response`() {
        val match = matchFixture()
        `when`(riotMatchClient.findMatchById("KR_1234567890")).thenReturn(match)

        val response = matchService.findByMatchId("KR_1234567890")

        verify(riotMatchClient).findMatchById("KR_1234567890")
        assertEquals("KR_1234567890", response.matchId)
        assertEquals(420, response.queueId)
        assertEquals("CLASSIC", response.gameMode)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), response.startedAt)
        assertEquals(1_800, response.durationSeconds)

        val participant = response.participants.single()
        assertEquals("test-puuid", participant.puuid)
        assertEquals("Hide on bush", participant.riotId.gameName)
        assertEquals("KR1", participant.riotId.tagLine)
        assertEquals(266, participant.championId)
        assertEquals("Aatrox", participant.championName)
        assertEquals(180, participant.laneMinionKills)
        assertEquals(12, participant.neutralMinionKills)
        assertEquals(12_345, participant.goldEarned)
        assertEquals(24_680, participant.championDamageDealt)
        assertEquals(listOf(3_073, 0, 3_364), participant.itemIds)
        assertEquals(listOf(4, 12), participant.summonerSpellIds)
        assertEquals(
            8_010,
            participant.perks.runePaths
                .single()
                .styleId,
        )

        val team = response.teams.single()
        assertEquals(100, team.teamId)
        assertEquals(listOf(238), team.bannedChampionIds)
        assertEquals(8, team.objectives.tower.killCount)
    }

    @Test
    fun `does not expose Riot infrastructure DTOs or excluded domain fields in the response contract`() {
        val propertyNames = MatchResponse::class.memberProperties.map { it.name }
        val responseTypeNames =
            listOf(
                MatchResponse::class,
                MatchParticipantResponse::class,
                RiotIdSnapshotResponse::class,
                MatchVisionResponse::class,
                MatchPerksResponse::class,
                MatchRunePathResponse::class,
                MatchTeamResponse::class,
                MatchObjectivesResponse::class,
                ObjectiveResultResponse::class,
            ).flatMap { responseType -> responseType.memberProperties.map { it.returnType.toString() } }

        assertFalse("gameVersion" in propertyNames)
        assertFalse("mapId" in propertyNames)
        assertFalse("platformId" in propertyNames)
        assertFalse("endedAt" in propertyNames)
        assertFalse(responseTypeNames.any { "infrastructure.riot" in it })
    }

    private fun matchFixture(): Match {
        val objective = ObjectiveResult(wasFirst = false, killCount = 0)
        return Match(
            matchId = "KR_1234567890",
            queueId = 420,
            gameMode = "CLASSIC",
            gameVersion = "16.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            endedAt = Instant.parse("2026-01-01T12:30:00Z"),
            duration = Duration.ofMinutes(30),
            participants =
                listOf(
                    MatchParticipant(
                        participantId = 1,
                        puuid = "test-puuid",
                        riotId = RiotIdSnapshot("Hide on bush", "KR1"),
                        teamId = 100,
                        won = true,
                        position = "TOP",
                        champion = MatchChampion(266, "Aatrox", 18),
                        kills = 8,
                        deaths = 2,
                        assists = 5,
                        pentaKills = 1,
                        laneMinionKills = 180,
                        neutralMinionKills = 12,
                        goldEarned = 12_345,
                        championDamageDealt = 24_680,
                        damageTaken = 17_890,
                        vision = MatchVision(score = 28, wardsPlaced = 9, wardsKilled = 3),
                        turretKills = 2,
                        itemIdsBySlot = listOf(3_073, 0, 3_364),
                        summonerSpellIds = listOf(4, 12),
                        perks =
                            MatchPerks(
                                offenseStatPerkId = 5_008,
                                flexStatPerkId = 5_010,
                                defenseStatPerkId = 5_011,
                                runePaths = listOf(MatchRunePath(8_010, listOf(8_005))),
                            ),
                        reportedChallenges =
                            ReportedMatchChallenges(
                                kda = 4.5,
                                killParticipation = 0.65,
                                damagePerMinute = 822.67,
                                teamDamagePercentage = 0.31,
                            ),
                    ),
                ),
            teams =
                listOf(
                    MatchTeam(
                        teamId = 100,
                        won = true,
                        bannedChampionIds = listOf(238),
                        objectives =
                            MatchObjectives(
                                atakhan = objective,
                                baron = objective,
                                champion = objective,
                                dragon = objective,
                                horde = objective,
                                inhibitor = objective,
                                riftHerald = objective,
                                tower = ObjectiveResult(wasFirst = true, killCount = 8),
                            ),
                    ),
                ),
        )
    }
}
