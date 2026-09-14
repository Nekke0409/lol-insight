package io.github.nekke0409.lolinsight.match.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant

internal object MatchParticipantMetricsCalculator {
    fun calculate(
        match: Match,
        participant: MatchParticipant,
    ): MatchParticipantMetrics {
        val teamParticipants = match.participants.filter { it.teamId == participant.teamId }
        val durationMinutes = match.duration.toMinutesFraction()
        val totalCs = participant.laneMinionKills.toDouble() + participant.neutralMinionKills.toDouble()
        val teamKills = teamParticipants.sumOf { it.kills.toLong() }
        val teamDamage = teamParticipants.sumOf { it.championDamageDealt.toLong() }

        return MatchParticipantMetrics(
            won = participant.won,
            kills = participant.kills,
            deaths = participant.deaths,
            assists = participant.assists,
            kda = (participant.kills.toDouble() + participant.assists) / maxOf(1, participant.deaths),
            csPerMinute = totalCs.perMinute(durationMinutes),
            goldPerMinute = participant.goldEarned.toDouble().perMinute(durationMinutes),
            damagePerMinute = participant.championDamageDealt.toDouble().perMinute(durationMinutes),
            visionPerMinute =
                participant.vision.score
                    .toDouble()
                    .perMinute(durationMinutes),
            killParticipation = (participant.kills.toDouble() + participant.assists).ratioOf(teamKills),
            damageShare = participant.championDamageDealt.toDouble().ratioOf(teamDamage),
        )
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

    private const val MILLIS_PER_MINUTE = 60_000.0
}

internal data class MatchParticipantMetrics(
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

internal fun MatchParticipant.kda(): Double = (kills.toDouble() + assists) / maxOf(1, deaths)
