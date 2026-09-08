package io.github.nekke0409.lolinsight.match.infrastructure.riot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchParticipantDto(
    val participantId: Int,
    val puuid: String,
    val riotIdGameName: String,
    val riotIdTagline: String,
    val teamId: Int,
    val win: Boolean,
    val teamPosition: String,
    val championId: Int,
    val championName: String,
    val champLevel: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val pentaKills: Int,
    val totalMinionsKilled: Int,
    val neutralMinionsKilled: Int,
    val goldEarned: Int,
    val totalDamageDealtToChampions: Int,
    val totalDamageTaken: Int,
    val visionScore: Int,
    val wardsPlaced: Int,
    val wardsKilled: Int,
    val turretKills: Int,
    val item0: Int,
    val item1: Int,
    val item2: Int,
    val item3: Int,
    val item4: Int,
    val item5: Int,
    val item6: Int,
    val summoner1Id: Int,
    val summoner2Id: Int,
    val perks: RiotMatchPerksDto,
    val challenges: RiotMatchChallengesDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchChallengesDto(
    val kda: Double? = null,
    val killParticipation: Double? = null,
    val damagePerMinute: Double? = null,
    val teamDamagePercentage: Double? = null,
)
