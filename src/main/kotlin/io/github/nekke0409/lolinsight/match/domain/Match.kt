package io.github.nekke0409.lolinsight.match.domain

import java.time.Duration
import java.time.Instant

data class Match(
    val matchId: String,
    val queueId: Int,
    val gameMode: String,
    val gameVersion: String,
    val mapId: Int,
    val platformId: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val duration: Duration,
    val participants: List<MatchParticipant>,
    val teams: List<MatchTeam>,
)

data class MatchParticipant(
    val participantId: Int,
    val puuid: String,
    val riotId: RiotIdSnapshot,
    val teamId: Int,
    val won: Boolean,
    val position: String,
    val champion: MatchChampion,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val pentaKills: Int,
    val laneMinionKills: Int,
    val neutralMinionKills: Int,
    val goldEarned: Int,
    val championDamageDealt: Int,
    val damageTaken: Int,
    val vision: MatchVision,
    val turretKills: Int,
    val itemIdsBySlot: List<Int>,
    val summonerSpellIds: List<Int>,
    val perks: MatchPerks,
    val reportedChallenges: ReportedMatchChallenges?,
)

data class RiotIdSnapshot(
    val gameName: String,
    val tagLine: String,
)

data class MatchChampion(
    val id: Int,
    val name: String,
    val level: Int,
)

data class MatchVision(
    val score: Int,
    val wardsPlaced: Int,
    val wardsKilled: Int,
)

data class MatchPerks(
    val offenseStatPerkId: Int,
    val flexStatPerkId: Int,
    val defenseStatPerkId: Int,
    val runePaths: List<MatchRunePath>,
)

data class MatchRunePath(
    val styleId: Int,
    val selectedRuneIds: List<Int>,
)

data class ReportedMatchChallenges(
    val kda: Double?,
    val killParticipation: Double?,
    val damagePerMinute: Double?,
    val teamDamagePercentage: Double?,
)

data class MatchTeam(
    val teamId: Int,
    val won: Boolean,
    val bannedChampionIds: List<Int>,
    val objectives: MatchObjectives,
)

data class MatchObjectives(
    val atakhan: ObjectiveResult,
    val baron: ObjectiveResult,
    val champion: ObjectiveResult,
    val dragon: ObjectiveResult,
    val horde: ObjectiveResult,
    val inhibitor: ObjectiveResult,
    val riftHerald: ObjectiveResult,
    val tower: ObjectiveResult,
)

data class ObjectiveResult(
    val wasFirst: Boolean,
    val killCount: Int,
)
