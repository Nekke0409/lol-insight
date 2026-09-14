package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetrics
import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetricsCalculator
import io.github.nekke0409.lolinsight.match.domain.Match
import org.springframework.stereotype.Component

@Component
class PlayerMatchStatisticsCalculator {
    fun calculate(
        targetPuuid: String,
        matches: List<Match>,
    ): PlayerMatchStatistics {
        val metrics = matches.mapNotNull { match -> match.toMetricsFor(targetPuuid) }

        if (metrics.isEmpty()) {
            return PlayerMatchStatistics.empty()
        }

        return PlayerMatchStatistics(
            games = metrics.size,
            wins = metrics.count { it.won },
            losses = metrics.count { !it.won },
            winRate = metrics.count { it.won }.toDouble() / metrics.size,
            averageKills = metrics.meanOf { it.kills.toDouble() },
            averageDeaths = metrics.meanOf { it.deaths.toDouble() },
            averageAssists = metrics.meanOf { it.assists.toDouble() },
            averageKda = metrics.meanOf { it.kda },
            averageCsPerMinute = metrics.meanOf { it.csPerMinute },
            averageGoldPerMinute = metrics.meanOf { it.goldPerMinute },
            averageDamagePerMinute = metrics.meanOf { it.damagePerMinute },
            averageVisionPerMinute = metrics.meanOf { it.visionPerMinute },
            averageKillParticipation = metrics.meanOf { it.killParticipation },
            averageDamageShare = metrics.meanOf { it.damageShare },
        )
    }

    private fun Match.toMetricsFor(targetPuuid: String): MatchMetrics? {
        val participant = participants.firstOrNull { it.puuid == targetPuuid } ?: return null
        return MatchParticipantMetricsCalculator.calculate(this, participant).toMatchMetrics()
    }

    private fun MatchParticipantMetrics.toMatchMetrics(): MatchMetrics =
        MatchMetrics(
            won = won,
            kills = kills,
            deaths = deaths,
            assists = assists,
            kda = kda,
            csPerMinute = csPerMinute,
            goldPerMinute = goldPerMinute,
            damagePerMinute = damagePerMinute,
            visionPerMinute = visionPerMinute,
            killParticipation = killParticipation,
            damageShare = damageShare,
        )

    private fun List<MatchMetrics>.meanOf(selector: (MatchMetrics) -> Double): Double = sumOf(selector) / size

    private data class MatchMetrics(
        val won: Boolean,
        val kills: Int,
        val deaths: Int,
        val assists: Int,
        val kda: Double,
        val csPerMinute: Double,
        val goldPerMinute: Double,
        val damagePerMinute: Double,
        val visionPerMinute: Double,
        val killParticipation: Double,
        val damageShare: Double,
    )
}

data class PlayerMatchStatistics(
    val games: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val averageKills: Double,
    val averageDeaths: Double,
    val averageAssists: Double,
    val averageKda: Double,
    val averageCsPerMinute: Double,
    val averageGoldPerMinute: Double,
    val averageDamagePerMinute: Double,
    val averageVisionPerMinute: Double,
    val averageKillParticipation: Double,
    val averageDamageShare: Double,
) {
    companion object {
        fun empty(): PlayerMatchStatistics =
            PlayerMatchStatistics(
                games = 0,
                wins = 0,
                losses = 0,
                winRate = 0.0,
                averageKills = 0.0,
                averageDeaths = 0.0,
                averageAssists = 0.0,
                averageKda = 0.0,
                averageCsPerMinute = 0.0,
                averageGoldPerMinute = 0.0,
                averageDamagePerMinute = 0.0,
                averageVisionPerMinute = 0.0,
                averageKillParticipation = 0.0,
                averageDamageShare = 0.0,
            )
    }
}
