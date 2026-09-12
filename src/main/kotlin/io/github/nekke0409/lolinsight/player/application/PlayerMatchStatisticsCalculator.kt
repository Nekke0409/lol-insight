package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
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
        val teamParticipants = participants.filter { it.teamId == participant.teamId }
        val durationMinutes = duration.toMinutesFraction()
        val totalCs = participant.laneMinionKills.toDouble() + participant.neutralMinionKills.toDouble()

        return MatchMetrics(
            won = participant.won,
            kills = participant.kills,
            deaths = participant.deaths,
            assists = participant.assists,
            kda = participant.kda(),
            csPerMinute = totalCs.perMinute(durationMinutes),
            goldPerMinute = participant.goldEarned.toDouble().perMinute(durationMinutes),
            damagePerMinute = participant.championDamageDealt.toDouble().perMinute(durationMinutes),
            visionPerMinute =
                participant.vision.score
                    .toDouble()
                    .perMinute(durationMinutes),
            killParticipation = participant.killParticipation(teamParticipants),
            damageShare = participant.damageShare(teamParticipants),
        )
    }

    private fun MatchParticipant.killParticipation(teamParticipants: List<MatchParticipant>): Double {
        val teamKills = teamParticipants.sumOf { it.kills.toLong() }
        return (kills.toDouble() + assists).ratioOf(teamKills)
    }

    private fun MatchParticipant.damageShare(teamParticipants: List<MatchParticipant>): Double {
        val teamDamage = teamParticipants.sumOf { it.championDamageDealt.toLong() }
        return championDamageDealt.toDouble().ratioOf(teamDamage)
    }

    private fun java.time.Duration.toMinutesFraction(): Double =
        if (isZero || isNegative) {
            0.0
        } else {
            toMillis().toDouble() / MILLIS_PER_MINUTE
        }

    private fun Double.perMinute(durationMinutes: Double): Double =
        if (durationMinutes == 0.0) {
            0.0
        } else {
            this / durationMinutes
        }

    private fun Double.ratioOf(denominator: Long): Double =
        if (denominator == 0L) {
            0.0
        } else {
            this / denominator
        }

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

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
    }
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
