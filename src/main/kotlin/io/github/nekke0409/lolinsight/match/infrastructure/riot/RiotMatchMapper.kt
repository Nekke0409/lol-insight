package io.github.nekke0409.lolinsight.match.infrastructure.riot

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
import java.time.Duration
import java.time.Instant

object RiotMatchMapper {
    fun toMatch(response: RiotMatchResponseDto): Match =
        response.info.let { info ->
            Match(
                matchId = response.metadata.matchId,
                queueId = info.queueId,
                gameMode = info.gameMode,
                gameVersion = info.gameVersion,
                mapId = info.mapId,
                platformId = info.platformId,
                startedAt = Instant.ofEpochMilli(info.gameStartTimestamp),
                endedAt = Instant.ofEpochMilli(info.gameEndTimestamp),
                duration = Duration.ofSeconds(info.gameDuration),
                participants = info.participants.map(::toParticipant),
                teams = info.teams.map(::toTeam),
            )
        }

    private fun toParticipant(participant: RiotMatchParticipantDto): MatchParticipant =
        MatchParticipant(
            participantId = participant.participantId,
            puuid = participant.puuid,
            riotId = RiotIdSnapshot(participant.riotIdGameName, participant.riotIdTagline),
            teamId = participant.teamId,
            won = participant.win,
            position = participant.teamPosition,
            champion = MatchChampion(participant.championId, participant.championName, participant.champLevel),
            kills = participant.kills,
            deaths = participant.deaths,
            assists = participant.assists,
            pentaKills = participant.pentaKills,
            laneMinionKills = participant.totalMinionsKilled,
            neutralMinionKills = participant.neutralMinionsKilled,
            goldEarned = participant.goldEarned,
            championDamageDealt = participant.totalDamageDealtToChampions,
            damageTaken = participant.totalDamageTaken,
            vision = MatchVision(participant.visionScore, participant.wardsPlaced, participant.wardsKilled),
            turretKills = participant.turretKills,
            itemIdsBySlot = participant.itemIds(),
            summonerSpellIds = listOf(participant.summoner1Id, participant.summoner2Id),
            perks = participant.perks.toMatchPerks(),
            reportedChallenges = participant.challenges?.toReportedChallenges(),
        )

    private fun RiotMatchParticipantDto.itemIds(): List<Int> = listOf(item0, item1, item2, item3, item4, item5, item6)

    private fun RiotMatchPerksDto.toMatchPerks(): MatchPerks =
        MatchPerks(
            offenseStatPerkId = statPerks.offense,
            flexStatPerkId = statPerks.flex,
            defenseStatPerkId = statPerks.defense,
            runePaths =
                styles.map { style ->
                    MatchRunePath(
                        styleId = style.style,
                        selectedRuneIds = style.selections.map { it.perk },
                    )
                },
        )

    private fun RiotMatchChallengesDto.toReportedChallenges(): ReportedMatchChallenges =
        ReportedMatchChallenges(
            kda = kda,
            killParticipation = killParticipation,
            damagePerMinute = damagePerMinute,
            teamDamagePercentage = teamDamagePercentage,
        )

    private fun toTeam(team: RiotMatchTeamDto): MatchTeam =
        MatchTeam(
            teamId = team.teamId,
            won = team.win,
            bannedChampionIds = team.bans.map { it.championId },
            objectives = team.objectives.toMatchObjectives(),
        )

    private fun RiotMatchObjectivesDto.toMatchObjectives(): MatchObjectives =
        MatchObjectives(
            atakhan = atakhan.toObjectiveResult(),
            baron = baron.toObjectiveResult(),
            champion = champion.toObjectiveResult(),
            dragon = dragon.toObjectiveResult(),
            horde = horde.toObjectiveResult(),
            inhibitor = inhibitor.toObjectiveResult(),
            riftHerald = riftHerald.toObjectiveResult(),
            tower = tower.toObjectiveResult(),
        )

    private fun RiotMatchObjectiveDto.toObjectiveResult(): ObjectiveResult = ObjectiveResult(wasFirst = first, killCount = kills)
}
