package io.github.nekke0409.lolinsight.match.infrastructure.riot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchTeamDto(
    val teamId: Int,
    val win: Boolean,
    val bans: List<RiotMatchBanDto>,
    val objectives: RiotMatchObjectivesDto,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchBanDto(
    val championId: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchObjectivesDto(
    val atakhan: RiotMatchObjectiveDto,
    val baron: RiotMatchObjectiveDto,
    val champion: RiotMatchObjectiveDto,
    val dragon: RiotMatchObjectiveDto,
    val horde: RiotMatchObjectiveDto,
    val inhibitor: RiotMatchObjectiveDto,
    val riftHerald: RiotMatchObjectiveDto,
    val tower: RiotMatchObjectiveDto,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchObjectiveDto(
    val first: Boolean,
    val kills: Int,
)
