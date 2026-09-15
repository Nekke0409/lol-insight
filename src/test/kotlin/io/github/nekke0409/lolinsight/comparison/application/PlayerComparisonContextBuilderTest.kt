package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerComparisonContextBuilderTest {
    private val builder = PlayerComparisonContextBuilder()
    private val rankContext = PlayerRankContext("GOLD", "I", Instant.parse("2026-09-14T01:23:45Z"))

    @Test
    fun `aggregates only the target player by position and champion-position`() {
        val context = buildContext(sampleMatches())

        assertEquals(PlayerComparisonContextPlayer("Hide on bush", "KR1"), context.player)
        assertEquals(TARGET_PUUID, context.targetPuuid)
        assertEquals(rankContext, context.rankContext)
        assertEquals(PlayerComparisonContextSample(requestedCount = 20, analyzedCount = 4), context.sample)
        assertEquals(
            listOf(
                PlayerPositionStatistics(
                    position = "MIDDLE",
                    games = 3,
                    wins = 2,
                    winRate = 2.0 / 3.0,
                    averageKda = 14.0 / 3.0,
                    averageCsPerMinute = 7.0,
                    averageGoldPerMinute = 500.0,
                    averageDamagePerMinute = 2_900.0 / 3.0,
                    averageVisionPerMinute = 7.0 / 6.0,
                    averageKillParticipation = 2.0 / 3.0,
                    averageDamageShare = 1.0,
                ),
                PlayerPositionStatistics(
                    position = "TOP",
                    games = 1,
                    wins = 1,
                    winRate = 1.0,
                    averageKda = 4.0,
                    averageCsPerMinute = 5.0,
                    averageGoldPerMinute = 400.0,
                    averageDamagePerMinute = 800.0,
                    averageVisionPerMinute = 1.0,
                    averageKillParticipation = 1.0,
                    averageDamageShare = 1.0,
                ),
            ),
            context.positionStatistics,
        )
        assertEquals(
            listOf(
                PlayerChampionPositionStatistics(
                    championId = 103,
                    position = "MIDDLE",
                    games = 2,
                    wins = 1,
                    winRate = 0.5,
                    averageKda = 5.0,
                    averageCsPerMinute = 7.5,
                    averageGoldPerMinute = 550.0,
                    averageDamagePerMinute = 1_050.0,
                    averageVisionPerMinute = 1.25,
                    averageKillParticipation = 0.5,
                    averageDamageShare = 1.0,
                ),
                PlayerChampionPositionStatistics(
                    championId = 86,
                    position = "MIDDLE",
                    games = 1,
                    wins = 1,
                    winRate = 1.0,
                    averageKda = 4.0,
                    averageCsPerMinute = 6.0,
                    averageGoldPerMinute = 400.0,
                    averageDamagePerMinute = 800.0,
                    averageVisionPerMinute = 1.0,
                    averageKillParticipation = 1.0,
                    averageDamageShare = 1.0,
                ),
                PlayerChampionPositionStatistics(
                    championId = 103,
                    position = "TOP",
                    games = 1,
                    wins = 1,
                    winRate = 1.0,
                    averageKda = 4.0,
                    averageCsPerMinute = 5.0,
                    averageGoldPerMinute = 400.0,
                    averageDamagePerMinute = 800.0,
                    averageVisionPerMinute = 1.0,
                    averageKillParticipation = 1.0,
                    averageDamageShare = 1.0,
                ),
            ),
            context.championPositionStatistics,
        )
        assertTrue(allDoubleValues(context).all(Double::isFinite))
    }

    @Test
    fun `keeps cohort ordering deterministic and supports an unavailable rank context`() {
        val matches = sampleMatches()

        val context = buildContext(matches, rankContext = null)
        val reversedContext = buildContext(matches.reversed(), rankContext = null)

        assertEquals(null, context.rankContext)
        assertEquals(context.positionStatistics, reversedContext.positionStatistics)
        assertEquals(context.championPositionStatistics, reversedContext.championPositionStatistics)
    }

    private fun buildContext(
        matches: List<Match>,
        rankContext: PlayerRankContext? = this.rankContext,
    ): PlayerComparisonContext =
        builder.build(
            player = PlayerRecentMatchHistoryPlayer(TARGET_PUUID, "Hide on bush", "KR1"),
            requestedCount = 20,
            targetPuuid = TARGET_PUUID,
            matches = matches,
            rankContext = rankContext,
        )

    private fun sampleMatches(): List<Match> =
        listOf(
            match(
                "KR_1",
                targetParticipant(103, "MIDDLE", kills = 10, deaths = 0, laneCs = 200, goldEarned = 12_000, damage = 30_000, vision = 30),
                otherParticipant(),
                duration = Duration.ofMinutes(20),
            ),
            match(
                "KR_2",
                targetParticipant(
                    103,
                    "MIDDLE",
                    won = false,
                    kills = 0,
                    deaths = 2,
                    laneCs = 50,
                    goldEarned = 5_000,
                    damage = 6_000,
                    vision = 10,
                ),
                duration = Duration.ofMinutes(10),
            ),
            match(
                "KR_3",
                targetParticipant(103, "TOP", kills = 4, deaths = 1, laneCs = 100, goldEarned = 8_000, damage = 16_000, vision = 20),
                duration = Duration.ofMinutes(20),
            ),
            match(
                "KR_4",
                targetParticipant(86, "MIDDLE", kills = 4, deaths = 1, laneCs = 120, goldEarned = 8_000, damage = 16_000, vision = 20),
                duration = Duration.ofMinutes(20),
            ),
        )

    private fun match(
        matchId: String,
        vararg participants: MatchParticipant,
        duration: Duration,
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
            duration = duration,
            participants = participants.toList(),
            teams =
                listOf(
                    MatchTeam(100, won = true, bannedChampionIds = emptyList(), objectives = matchObjectives(objective)),
                    MatchTeam(200, won = false, bannedChampionIds = emptyList(), objectives = matchObjectives(objective)),
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

    private fun targetParticipant(
        championId: Int,
        position: String,
        won: Boolean = true,
        kills: Int,
        deaths: Int,
        laneCs: Int,
        goldEarned: Int,
        damage: Int,
        vision: Int,
    ): MatchParticipant =
        participant(
            puuid = TARGET_PUUID,
            championId = championId,
            position = position,
            won = won,
            kills = kills,
            deaths = deaths,
            laneCs = laneCs,
            goldEarned = goldEarned,
            damage = damage,
            vision = vision,
        )

    private fun otherParticipant(): MatchParticipant =
        participant(
            puuid = "other-puuid",
            championId = 157,
            position = "MIDDLE",
            kills = 100,
            deaths = 0,
            laneCs = 0,
            goldEarned = 0,
            damage = 0,
            vision = 0,
            teamId = 200,
        )

    private fun participant(
        puuid: String,
        championId: Int,
        position: String,
        won: Boolean = true,
        kills: Int,
        deaths: Int,
        laneCs: Int,
        goldEarned: Int,
        damage: Int,
        vision: Int,
        teamId: Int = 100,
    ): MatchParticipant =
        MatchParticipant(
            participantId = participantSequence++,
            puuid = puuid,
            riotId = RiotIdSnapshot("Player", "KR1"),
            teamId = teamId,
            won = won,
            position = position,
            champion = MatchChampion(championId, "Champion-$championId", 18),
            kills = kills,
            deaths = deaths,
            assists = 0,
            pentaKills = 0,
            laneMinionKills = laneCs,
            neutralMinionKills = 0,
            goldEarned = goldEarned,
            championDamageDealt = damage,
            damageTaken = 0,
            vision = MatchVision(score = vision, wardsPlaced = 0, wardsKilled = 0),
            turretKills = 0,
            itemIdsBySlot = emptyList(),
            summonerSpellIds = emptyList(),
            perks = MatchPerks(0, 0, 0, emptyList()),
            reportedChallenges = null,
        )

    private fun allDoubleValues(context: PlayerComparisonContext): List<Double> =
        (context.positionStatistics + context.championPositionStatistics).flatMap {
            listOf(
                it.winRate,
                it.averageKda,
                it.averageCsPerMinute,
                it.averageGoldPerMinute,
                it.averageDamagePerMinute,
                it.averageVisionPerMinute,
                it.averageKillParticipation,
                it.averageDamageShare,
            )
        }

    private companion object {
        const val TARGET_PUUID = "target-puuid"
        var participantSequence = 1
    }
}
