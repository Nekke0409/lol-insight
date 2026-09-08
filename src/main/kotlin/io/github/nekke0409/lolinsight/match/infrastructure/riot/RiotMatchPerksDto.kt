package io.github.nekke0409.lolinsight.match.infrastructure.riot

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchPerksDto(
    val statPerks: RiotMatchStatPerksDto,
    val styles: List<RiotMatchPerkStyleDto>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchStatPerksDto(
    val defense: Int,
    val flex: Int,
    val offense: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchPerkStyleDto(
    val style: Int,
    val selections: List<RiotMatchPerkSelectionDto>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RiotMatchPerkSelectionDto(
    val perk: Int,
)
