package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext

data class PlayerComparisonContext(
    val player: PlayerComparisonContextPlayer,
    val targetPuuid: String,
    val rankContext: PlayerRankContext?,
    val sample: PlayerComparisonContextSample,
    val positionStatistics: List<PlayerPositionStatistics>,
    val championPositionStatistics: List<PlayerChampionPositionStatistics>,
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

sealed interface PlayerScopeStatistics {
    val position: String
    val games: Int
    val wins: Int
    val winRate: Double
    val averageKda: Double
    val averageCsPerMinute: Double
    val averageGoldPerMinute: Double
    val averageDamagePerMinute: Double
    val averageVisionPerMinute: Double
    val averageKillParticipation: Double
    val averageDamageShare: Double
}

data class PlayerPositionStatistics(
    override val position: String,
    override val games: Int,
    override val wins: Int,
    override val winRate: Double,
    override val averageKda: Double,
    override val averageCsPerMinute: Double,
    override val averageGoldPerMinute: Double,
    override val averageDamagePerMinute: Double,
    override val averageVisionPerMinute: Double,
    override val averageKillParticipation: Double,
    override val averageDamageShare: Double,
) : PlayerScopeStatistics {
    init {
        validate(position, games, wins, metricValues())
    }

    private fun metricValues(): List<Double> =
        listOf(
            winRate,
            averageKda,
            averageCsPerMinute,
            averageGoldPerMinute,
            averageDamagePerMinute,
            averageVisionPerMinute,
            averageKillParticipation,
            averageDamageShare,
        )
}

data class PlayerChampionPositionStatistics(
    val championId: Int,
    override val position: String,
    override val games: Int,
    override val wins: Int,
    override val winRate: Double,
    override val averageKda: Double,
    override val averageCsPerMinute: Double,
    override val averageGoldPerMinute: Double,
    override val averageDamagePerMinute: Double,
    override val averageVisionPerMinute: Double,
    override val averageKillParticipation: Double,
    override val averageDamageShare: Double,
) : PlayerScopeStatistics {
    init {
        require(championId > 0) { "championId must be positive" }
        validate(position, games, wins, metricValues())
    }

    private fun metricValues(): List<Double> =
        listOf(
            winRate,
            averageKda,
            averageCsPerMinute,
            averageGoldPerMinute,
            averageDamagePerMinute,
            averageVisionPerMinute,
            averageKillParticipation,
            averageDamageShare,
        )
}

private fun validate(
    position: String,
    games: Int,
    wins: Int,
    metricValues: List<Double>,
) {
    require(position.isNotBlank()) { "position must not be blank" }
    require(games > 0) { "games must be positive" }
    require(wins in 0..games) { "wins must be between zero and games" }
    require(metricValues.all(Double::isFinite)) { "cohort statistics must be finite" }
}
