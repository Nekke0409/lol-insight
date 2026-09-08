package io.github.nekke0409.lolinsight.match.infrastructure.riot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchResponseDto(
    val metadata: RiotMatchMetadataDto,
    val info: RiotMatchInfoDto,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchMetadataDto(
    val matchId: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchInfoDto(
    val gameStartTimestamp: Long,
    val gameEndTimestamp: Long,
    val gameDuration: Long,
    val gameMode: String,
    val gameVersion: String,
    val mapId: Int,
    val platformId: String,
    val queueId: Int,
    val participants: List<RiotMatchParticipantDto>,
    val teams: List<RiotMatchTeamDto>,
)
