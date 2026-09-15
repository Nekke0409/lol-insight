package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext

data class PlayerComparisonContext(
    val player: PlayerComparisonContextPlayer,
    val targetPuuid: String,
    val rankContext: PlayerRankContext?,
    val sample: PlayerComparisonContextSample,
    val cohortStatistics: List<PlayerCohortStatistics>,
) {
    init {
        require(targetPuuid.isNotBlank()) { "targetPuuid must not be blank" }
    }
}

data class PlayerComparisonContextPlayer(
    val gameName: String,
    val tagLine: String,
)

data class PlayerComparisonContextSample(
    val requestedCount: Int,
    val analyzedCount: Int,
)

data class PlayerCohortStatistics(
    val championId: Int,
    val position: String,
    val games: Int,
    val wins: Int,
    val winRate: Double,
    val averageKda: Double,
    val averageCsPerMinute: Double,
    val averageGoldPerMinute: Double,
    val averageDamagePerMinute: Double,
    val averageVisionPerMinute: Double,
    val averageKillParticipation: Double,
    val averageDamageShare: Double,
) {
    init {
        require(championId > 0) { "championId must be positive" }
        require(position.isNotBlank()) { "position must not be blank" }
        require(games > 0) { "games must be positive" }
        require(wins in 0..games) { "wins must be between zero and games" }
        require(
            listOf(
                winRate,
                averageKda,
                averageCsPerMinute,
                averageGoldPerMinute,
                averageDamagePerMinute,
                averageVisionPerMinute,
                averageKillParticipation,
                averageDamageShare,
            ).all(Double::isFinite),
        ) { "cohort statistics must be finite" }
    }
}
