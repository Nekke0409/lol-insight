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
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerMatchStatisticsCalculatorTest {
    private val calculator = PlayerMatchStatisticsCalculator()

    @Test
    fun `calculates target statistics from the target team only`() {
        val target =
            participant(
                puuid = "target-puuid",
                teamId = 100,
                won = true,
                kills = 10,
                deaths = 0,
                assists = 5,
                laneCs = 180,
                neutralCs = 20,
                goldEarned = 12_000,
                championDamageDealt = 30_000,
                visionScore = 30,
            )
        val ally = participant(puuid = "ally-puuid", teamId = 100, kills = 10, championDamageDealt = 20_000)
        val opponent =
            participant(
                puuid = "opponent-puuid",
                teamId = 200,
                kills = 100,
                championDamageDealt = 1_000_000,
            )

        val statistics = calculator.calculate("target-puuid", listOf(match(Duration.ofMinutes(20), target, ally, opponent)))

        assertEquals(1, statistics.games)
        assertEquals(1, statistics.wins)
        assertEquals(0, statistics.losses)
        assertDoubleEquals(1.0, statistics.winRate)
        assertDoubleEquals(10.0, statistics.averageKills)
        assertDoubleEquals(0.0, statistics.averageDeaths)
        assertDoubleEquals(5.0, statistics.averageAssists)
        assertDoubleEquals(15.0, statistics.averageKda)
        assertDoubleEquals(10.0, statistics.averageCsPerMinute)
        assertDoubleEquals(600.0, statistics.averageGoldPerMinute)
        assertDoubleEquals(1_500.0, statistics.averageDamagePerMinute)
        assertDoubleEquals(1.5, statistics.averageVisionPerMinute)
        assertDoubleEquals(0.75, statistics.averageKillParticipation)
        assertDoubleEquals(0.6, statistics.averageDamageShare)
    }

    @Test
    fun `uses arithmetic means and excludes Matches without the target participant`() {
        val firstMatch =
            match(
                Duration.ofMinutes(20),
                participant(
                    puuid = "target-puuid",
                    teamId = 100,
                    won = true,
                    kills = 10,
                    deaths = 0,
                    assists = 5,
                    laneCs = 180,
                    neutralCs = 20,
                    goldEarned = 12_000,
                    championDamageDealt = 30_000,
                    visionScore = 30,
                ),
                participant(puuid = "ally-puuid", teamId = 100, kills = 10, championDamageDealt = 20_000),
            )
        val secondMatch =
            match(
                Duration.ofMinutes(10),
                participant(
                    puuid = "target-puuid",
                    teamId = 100,
                    won = false,
                    kills = 0,
                    deaths = 2,
                    assists = 4,
                    laneCs = 100,
                    goldEarned = 5_000,
                    championDamageDealt = 5_000,
                    visionScore = 10,
                ),
            )
        val matchWithoutTarget = match(Duration.ofMinutes(30), participant(puuid = "other-puuid", teamId = 200))

        val statistics = calculator.calculate("target-puuid", listOf(firstMatch, secondMatch, matchWithoutTarget))

        assertEquals(2, statistics.games)
        assertEquals(1, statistics.wins)
        assertEquals(1, statistics.losses)
        assertDoubleEquals(0.5, statistics.winRate)
        assertDoubleEquals(5.0, statistics.averageKills)
        assertDoubleEquals(1.0, statistics.averageDeaths)
        assertDoubleEquals(4.5, statistics.averageAssists)
        assertDoubleEquals(8.5, statistics.averageKda)
        assertDoubleEquals(10.0, statistics.averageCsPerMinute)
        assertDoubleEquals(550.0, statistics.averageGoldPerMinute)
        assertDoubleEquals(1_000.0, statistics.averageDamagePerMinute)
        assertDoubleEquals(1.25, statistics.averageVisionPerMinute)
        assertDoubleEquals(0.375, statistics.averageKillParticipation)
        assertDoubleEquals(0.8, statistics.averageDamageShare)
    }

    @Test
    fun `returns finite zero metrics for empty and zero-denominator samples`() {
        val zeroSample =
            calculator.calculate(
                "target-puuid",
                listOf(
                    match(
                        Duration.ZERO,
                        participant(
                            puuid = "target-puuid",
                            teamId = 100,
                            kills = 0,
                            deaths = 0,
                            assists = 0,
                            laneCs = 0,
                            neutralCs = 0,
                            goldEarned = 0,
                            championDamageDealt = 0,
                            visionScore = 0,
                        ),
                        participant(
                            puuid = "ally-puuid",
                            teamId = 100,
                            kills = 0,
                            championDamageDealt = 0,
                        ),
                    ),
                ),
            )
        val emptySample = calculator.calculate("target-puuid", emptyList())

        assertEquals(1, zeroSample.games)
        assertDoubleEquals(0.0, zeroSample.averageKda)
        assertDoubleEquals(0.0, zeroSample.averageCsPerMinute)
        assertDoubleEquals(0.0, zeroSample.averageGoldPerMinute)
        assertDoubleEquals(0.0, zeroSample.averageDamagePerMinute)
        assertDoubleEquals(0.0, zeroSample.averageVisionPerMinute)
        assertDoubleEquals(0.0, zeroSample.averageKillParticipation)
        assertDoubleEquals(0.0, zeroSample.averageDamageShare)
        assertTrue(allDoubleValues(zeroSample).all(Double::isFinite))
        assertEquals(PlayerMatchStatistics.empty(), emptySample)
        assertTrue(allDoubleValues(emptySample).all(Double::isFinite))
    }

    private fun match(
        duration: Duration,
        vararg participants: MatchParticipant,
    ): Match {
        val objective = ObjectiveResult(wasFirst = false, killCount = 0)
        return Match(
            matchId = "KR_${participants.joinToString("_") { it.participantId.toString() }}",
            queueId = 420,
            gameMode = "CLASSIC",
            gameVersion = "16.1",
            mapId = 11,
            platformId = "KR",
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            endedAt = Instant.parse("2026-01-01T12:30:00Z"),
            duration = duration,
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
        laneCs: Int = 0,
        neutralCs: Int = 0,
        goldEarned: Int = 0,
        championDamageDealt: Int = 0,
        visionScore: Int = 0,
    ): MatchParticipant =
        MatchParticipant(
            participantId = participantSequence++,
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
            laneMinionKills = laneCs,
            neutralMinionKills = neutralCs,
            goldEarned = goldEarned,
            championDamageDealt = championDamageDealt,
            damageTaken = 0,
            vision = MatchVision(score = visionScore, wardsPlaced = 0, wardsKilled = 0),
            turretKills = 0,
            itemIdsBySlot = emptyList(),
            summonerSpellIds = emptyList(),
            perks = MatchPerks(0, 0, 0, emptyList()),
            reportedChallenges = null,
        )

    private fun allDoubleValues(statistics: PlayerMatchStatistics): List<Double> =
        listOf(
            statistics.winRate,
            statistics.averageKills,
            statistics.averageDeaths,
            statistics.averageAssists,
            statistics.averageKda,
            statistics.averageCsPerMinute,
            statistics.averageGoldPerMinute,
            statistics.averageDamagePerMinute,
            statistics.averageVisionPerMinute,
            statistics.averageKillParticipation,
            statistics.averageDamageShare,
        )

    private fun assertDoubleEquals(
        expected: Double,
        actual: Double,
    ) {
        assertEquals(expected, actual, 0.000001)
    }

    private companion object {
        var participantSequence = 1
    }
}
