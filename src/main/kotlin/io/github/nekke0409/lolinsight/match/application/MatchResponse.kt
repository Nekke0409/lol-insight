package io.github.nekke0409.lolinsight.match.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchObjectives
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.MatchPerks
import io.github.nekke0409.lolinsight.match.domain.MatchRunePath
import io.github.nekke0409.lolinsight.match.domain.MatchTeam
import io.github.nekke0409.lolinsight.match.domain.MatchVision
import io.github.nekke0409.lolinsight.match.domain.ObjectiveResult
import io.github.nekke0409.lolinsight.match.domain.RiotIdSnapshot
import java.time.Instant

data class MatchResponse(
    val matchId: String,
    val queueId: Int,
    val gameMode: String,
    val startedAt: Instant,
    val durationSeconds: Long,
    val participants: List<MatchParticipantResponse>,
    val teams: List<MatchTeamResponse>,
) {
    companion object {
        fun from(match: Match): MatchResponse =
            MatchResponse(
                matchId = match.matchId,
                queueId = match.queueId,
                gameMode = match.gameMode,
                startedAt = match.startedAt,
                durationSeconds = match.duration.seconds,
                participants = match.participants.map(::MatchParticipantResponse),
                teams = match.teams.map(::MatchTeamResponse),
            )
    }
}

data class MatchParticipantResponse(
    val puuid: String,
    val riotId: RiotIdSnapshotResponse,
    val championId: Int,
    val championName: String,
    val teamId: Int,
    val position: String,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val win: Boolean,
    val laneMinionKills: Int,
    val neutralMinionKills: Int,
    val goldEarned: Int,
    val championDamageDealt: Int,
    val vision: MatchVisionResponse,
    val itemIds: List<Int>,
    val summonerSpellIds: List<Int>,
    val perks: MatchPerksResponse,
) {
    constructor(participant: MatchParticipant) : this(
        puuid = participant.puuid,
        riotId = RiotIdSnapshotResponse(participant.riotId),
        championId = participant.champion.id,
        championName = participant.champion.name,
        teamId = participant.teamId,
        position = participant.position,
        kills = participant.kills,
        deaths = participant.deaths,
        assists = participant.assists,
        win = participant.won,
        laneMinionKills = participant.laneMinionKills,
        neutralMinionKills = participant.neutralMinionKills,
        goldEarned = participant.goldEarned,
        championDamageDealt = participant.championDamageDealt,
        vision = MatchVisionResponse(participant.vision),
        itemIds = participant.itemIdsBySlot,
        summonerSpellIds = participant.summonerSpellIds,
        perks = MatchPerksResponse(participant.perks),
    )
}

data class RiotIdSnapshotResponse(
    val gameName: String,
    val tagLine: String,
) {
    constructor(riotId: RiotIdSnapshot) : this(riotId.gameName, riotId.tagLine)
}

data class MatchVisionResponse(
    val score: Int,
    val wardsPlaced: Int,
    val wardsKilled: Int,
) {
    constructor(vision: MatchVision) : this(vision.score, vision.wardsPlaced, vision.wardsKilled)
}

data class MatchPerksResponse(
    val offenseStatPerkId: Int,
    val flexStatPerkId: Int,
    val defenseStatPerkId: Int,
    val runePaths: List<MatchRunePathResponse>,
) {
    constructor(perks: MatchPerks) : this(
        offenseStatPerkId = perks.offenseStatPerkId,
        flexStatPerkId = perks.flexStatPerkId,
        defenseStatPerkId = perks.defenseStatPerkId,
        runePaths = perks.runePaths.map(::MatchRunePathResponse),
    )
}

data class MatchRunePathResponse(
    val styleId: Int,
    val selectedRuneIds: List<Int>,
) {
    constructor(runePath: MatchRunePath) : this(runePath.styleId, runePath.selectedRuneIds)
}

data class MatchTeamResponse(
    val teamId: Int,
    val win: Boolean,
    val bannedChampionIds: List<Int>,
    val objectives: MatchObjectivesResponse,
) {
    constructor(team: MatchTeam) : this(
        teamId = team.teamId,
        win = team.won,
        bannedChampionIds = team.bannedChampionIds,
        objectives = MatchObjectivesResponse(team.objectives),
    )
}

data class MatchObjectivesResponse(
    val atakhan: ObjectiveResultResponse,
    val baron: ObjectiveResultResponse,
    val champion: ObjectiveResultResponse,
    val dragon: ObjectiveResultResponse,
    val horde: ObjectiveResultResponse,
    val inhibitor: ObjectiveResultResponse,
    val riftHerald: ObjectiveResultResponse,
    val tower: ObjectiveResultResponse,
) {
    constructor(objectives: MatchObjectives) : this(
        atakhan = ObjectiveResultResponse(objectives.atakhan),
        baron = ObjectiveResultResponse(objectives.baron),
        champion = ObjectiveResultResponse(objectives.champion),
        dragon = ObjectiveResultResponse(objectives.dragon),
        horde = ObjectiveResultResponse(objectives.horde),
        inhibitor = ObjectiveResultResponse(objectives.inhibitor),
        riftHerald = ObjectiveResultResponse(objectives.riftHerald),
        tower = ObjectiveResultResponse(objectives.tower),
    )
}

data class ObjectiveResultResponse(
    val wasFirst: Boolean,
    val killCount: Int,
) {
    constructor(objective: ObjectiveResult) : this(objective.wasFirst, objective.killCount)
}
