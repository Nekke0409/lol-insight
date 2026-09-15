package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetrics
import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetricsCalculator
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import io.github.nekke0409.lolinsight.rank.application.PlayerRankContext
import org.springframework.stereotype.Component

@Component
class PlayerComparisonContextBuilder {
    fun build(
        player: PlayerRecentMatchHistoryPlayer,
        requestedCount: Int,
        targetPuuid: String,
        matches: List<Match>,
        rankContext: PlayerRankContext?,
    ): PlayerComparisonContext {
        val cohortStatistics =
            matches
                .mapNotNull { match -> match.toObservationFor(targetPuuid) }
                .toCohortStatistics()

        return PlayerComparisonContext(
            player = PlayerComparisonContextPlayer(player.gameName, player.tagLine),
            targetPuuid = targetPuuid,
            rankContext = rankContext,
            sample = PlayerComparisonContextSample(requestedCount = requestedCount, analyzedCount = cohortStatistics.sumOf { it.games }),
            cohortStatistics = cohortStatistics,
        )
    }

    private fun Match.toObservationFor(targetPuuid: String): CohortObservation? {
        val participant = participants.firstOrNull { it.puuid == targetPuuid } ?: return null
        return CohortObservation(
            participant = participant,
            metrics = MatchParticipantMetricsCalculator.calculate(this, participant),
        )
    }

    private fun List<CohortObservation>.toCohortStatistics(): List<PlayerCohortStatistics> =
        groupBy { observation -> CohortKey(observation.participant.champion.id, observation.participant.position) }
            .map { (cohort, observations) ->
                PlayerCohortStatistics(
                    championId = cohort.championId,
                    position = cohort.position,
                    games = observations.size,
                    wins = observations.count { it.metrics.won },
                    winRate = observations.count { it.metrics.won }.toDouble() / observations.size,
                    averageKda = observations.meanOf { it.metrics.kda },
                    averageCsPerMinute = observations.meanOf { it.metrics.csPerMinute },
                    averageGoldPerMinute = observations.meanOf { it.metrics.goldPerMinute },
                    averageDamagePerMinute = observations.meanOf { it.metrics.damagePerMinute },
                    averageVisionPerMinute = observations.meanOf { it.metrics.visionPerMinute },
                    averageKillParticipation = observations.meanOf { it.metrics.killParticipation },
                    averageDamageShare = observations.meanOf { it.metrics.damageShare },
                )
            }.sortedWith(
                compareByDescending<PlayerCohortStatistics> { it.games }
                    .thenBy { it.position }
                    .thenBy { it.championId },
            )

    private fun List<CohortObservation>.meanOf(selector: (CohortObservation) -> Double): Double = sumOf(selector) / size

    private data class CohortObservation(
        val participant: MatchParticipant,
        val metrics: MatchParticipantMetrics,
    )

    private data class CohortKey(
        val championId: Int,
        val position: String,
    )
}
