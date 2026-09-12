package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatistics

data class PlayerAnalysisFeature(
    val player: PlayerAnalysisFeaturePlayer,
    val sample: PlayerAnalysisFeatureSample,
    val overall: PlayerMatchStatistics,
    val championStats: List<PlayerAnalysisChampionStat>,
    val positionStats: List<PlayerAnalysisPositionStat>,
)

data class PlayerAnalysisFeaturePlayer(
    val gameName: String,
    val tagLine: String,
)

data class PlayerAnalysisFeatureSample(
    val requestedCount: Int,
    val analyzedCount: Int,
)

data class PlayerAnalysisChampionStat(
    val championId: Int,
    val championName: String,
    val games: Int,
    val wins: Int,
    val winRate: Double,
    val averageKda: Double,
)

data class PlayerAnalysisPositionStat(
    val position: String,
    val games: Int,
    val wins: Int,
    val winRate: Double,
    val averageKda: Double,
)
