package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchChampion
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatistics
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatisticsCalculator
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerAnalysisFeatureBuilderTest {
    private val builder = PlayerAnalysisFeatureBuilder()
    private val statisticsCalculator = PlayerMatchStatisticsCalculator()

    @Test
    fun `preserves overall statistics and aggregates only the target player by champion and position`() {
        val matches = sampleMatches()
        val overall = statisticsCalculator.calculate(TARGET_PUUID, matches)

        val feature = buildFeature(matches, overall)

        assertEquals(PlayerAnalysisFeaturePlayer("Hide on bush", "KR1"), feature.player)
        assertEquals(PlayerAnalysisFeatureSample(requestedCount = 20, analyzedCount = 4), feature.sample)
        assertEquals(overall, feature.overall)
        assertEquals(4, feature.overall.games)
        assertEquals(
            listOf(
                PlayerAnalysisChampionStat(266, "Aatrox", games = 2, wins = 1, winRate = 0.5, averageKda = 4.0),
                PlayerAnalysisChampionStat(103, "Ahri", games = 1, wins = 0, winRate = 0.0, averageKda = 1.0),
                PlayerAnalysisChampionStat(86, "Garen", games = 1, wins = 1, winRate = 1.0, averageKda = 4.0),
            ),
            feature.championStats,
        )
        assertEquals(
            listOf(
                PlayerAnalysisPositionStat("TOP", games = 2, wins = 1, winRate = 0.5, averageKda = 4.0),
                PlayerAnalysisPositionStat("JUNGLE", games = 1, wins = 1, winRate = 1.0, averageKda = 4.0),
                PlayerAnalysisPositionStat("MIDDLE", games = 1, wins = 0, winRate = 0.0, averageKda = 1.0),
            ),
            feature.positionStats,
        )
        assertTrue(allDoubleValues(feature).all(Double::isFinite))
    }

    @Test
    fun `returns empty finite breakdowns when the target participant is absent`() {
        val matches = listOf(match("KR_without_target", participant("other-puuid", 157, "Yasuo", "MIDDLE")))

        val feature = buildFeature(matches, PlayerMatchStatistics.empty())

        assertEquals(PlayerAnalysisFeatureSample(requestedCount = 20, analyzedCount = 0), feature.sample)
        assertEquals(PlayerMatchStatistics.empty(), feature.overall)
        assertTrue(feature.championStats.isEmpty())
        assertTrue(feature.positionStats.isEmpty())
        assertTrue(allDoubleValues(feature).all(Double::isFinite))
    }

    @Test
    fun `orders equivalent breakdowns deterministically regardless of Match input order`() {
        val matches = sampleMatches()

        val feature = buildFeature(matches, statisticsCalculator.calculate(TARGET_PUUID, matches))
        val reversedFeature = buildFeature(matches.reversed(), statisticsCalculator.calculate(TARGET_PUUID, matches.reversed()))

        assertEquals(feature.championStats, reversedFeature.championStats)
        assertEquals(feature.positionStats, reversedFeature.positionStats)
    }

    private fun buildFeature(
        matches: List<Match>,
        overall: PlayerMatchStatistics,
    ): PlayerAnalysisFeature =
        builder.build(
            player = PlayerRecentMatchHistoryPlayer(TARGET_PUUID, "Hide on bush", "KR1"),
            requestedCount = 20,
            targetPuuid = TARGET_PUUID,
            matches = matches,
            overall = overall,
        )

    private fun sampleMatches(): List<Match> =
        listOf(
            match(
                "KR_1",
                participant(TARGET_PUUID, 266, "Aatrox", "TOP", won = true, kills = 4, deaths = 0, assists = 2),
                participant("ally-puuid", 157, "Yasuo", "MIDDLE", won = true, kills = 12, deaths = 1, assists = 5),
            ),
            match(
                "KR_2",
                participant(TARGET_PUUID, 266, "Aatrox", "TOP", won = false, kills = 2, deaths = 2, assists = 2),
                participant("opponent-puuid", 86, "Garen", "TOP", won = true, kills = 10, deaths = 0, assists = 0),
            ),
            match(
                "KR_3",
                participant(TARGET_PUUID, 86, "Garen", "JUNGLE", won = true, kills = 0, deaths = 1, assists = 4),
                participant("ally-puuid", 103, "Ahri", "MIDDLE", won = true, kills = 9, deaths = 0, assists = 3),
            ),
            match(
                "KR_4",
                participant(TARGET_PUUID, 103, "Ahri", "MIDDLE", won = false, kills = 3, deaths = 3, assists = 0),
            ),
        )

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

    private fun participant(
        puuid: String,
        championId: Int,
        championName: String,
        position: String,
        won: Boolean = true,
        kills: Int = 0,
        deaths: Int = 0,
        assists: Int = 0,
    ): MatchParticipant =
        MatchParticipant(
            participantId = participantSequence++,
            puuid = puuid,
            riotId = RiotIdSnapshot("Player", "KR1"),
            teamId = 100,
            won = won,
            position = position,
            champion = MatchChampion(championId, championName, 18),
            kills = kills,
            deaths = deaths,
            assists = assists,
            pentaKills = 0,
            laneMinionKills = 0,
            neutralMinionKills = 0,
            goldEarned = 0,
            championDamageDealt = 0,
            damageTaken = 0,
            vision = MatchVision(score = 0, wardsPlaced = 0, wardsKilled = 0),
            turretKills = 0,
            itemIdsBySlot = emptyList(),
            summonerSpellIds = emptyList(),
            perks = MatchPerks(0, 0, 0, emptyList()),
            reportedChallenges = null,
        )

    private fun allDoubleValues(feature: PlayerAnalysisFeature): List<Double> =
        listOf(
            feature.overall.winRate,
            feature.overall.averageKills,
            feature.overall.averageDeaths,
            feature.overall.averageAssists,
            feature.overall.averageKda,
            feature.overall.averageCsPerMinute,
            feature.overall.averageGoldPerMinute,
            feature.overall.averageDamagePerMinute,
            feature.overall.averageVisionPerMinute,
            feature.overall.averageKillParticipation,
            feature.overall.averageDamageShare,
        ) + feature.championStats.flatMap { listOf(it.winRate, it.averageKda) } +
            feature.positionStats.flatMap { listOf(it.winRate, it.averageKda) }

    private companion object {
        const val TARGET_PUUID = "target-puuid"
        var participantSequence = 1
    }
}
