package io.github.nekke0409.lolinsight.comparison.application

import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetrics
import io.github.nekke0409.lolinsight.match.application.MatchParticipantMetricsCalculator
import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.match.domain.RankedSoloQueue
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
        val observations =
            matches
                .asSequence()
                .filter { it.queueId == RankedSoloQueue.ID }
                .mapNotNull { match -> match.toObservationFor(targetPuuid) }
                .toList()

        return PlayerComparisonContext(
            player = PlayerComparisonContextPlayer(player.gameName, player.tagLine),
            targetPuuid = targetPuuid,
            rankContext = rankContext,
            sample = PlayerComparisonContextSample(requestedCount = requestedCount, analyzedCount = observations.size),
            positionStatistics = observations.toPositionStatistics(),
            championPositionStatistics = observations.toChampionPositionStatistics(),
        )
    }

    private fun Match.toObservationFor(targetPuuid: String): CohortObservation? {
        val participant = participants.firstOrNull { it.puuid == targetPuuid } ?: return null
        return CohortObservation(
            participant = participant,
            metrics = MatchParticipantMetricsCalculator.calculate(this, participant),
        )
    }

    private fun List<CohortObservation>.toPositionStatistics(): List<PlayerPositionStatistics> =
        groupBy { it.participant.position }
            .map { (position, observations) -> observations.toStatistics().toPositionStatistics(position) }
            .sortedWith(compareByDescending<PlayerPositionStatistics> { it.games }.thenBy { it.position })

    private fun List<CohortObservation>.toChampionPositionStatistics(): List<PlayerChampionPositionStatistics> =
        groupBy { observation -> ChampionPositionKey(observation.participant.champion.id, observation.participant.position) }
            .map { (cohort, observations) -> observations.toStatistics().toChampionPositionStatistics(cohort) }
            .sortedWith(
                compareByDescending<PlayerChampionPositionStatistics> { it.games }
                    .thenBy { it.position }
                    .thenBy { it.championId },
            )

    private fun List<CohortObservation>.meanOf(selector: (CohortObservation) -> Double): Double = sumOf(selector) / size

    private fun List<CohortObservation>.toStatistics(): PlayerScopeStatisticsValues =
        PlayerScopeStatisticsValues(
            games = size,
            wins = count { it.metrics.won },
            winRate = count { it.metrics.won }.toDouble() / size,
            averageKda = meanOf { it.metrics.kda },
            averageCsPerMinute = meanOf { it.metrics.csPerMinute },
            averageGoldPerMinute = meanOf { it.metrics.goldPerMinute },
            averageDamagePerMinute = meanOf { it.metrics.damagePerMinute },
            averageVisionPerMinute = meanOf { it.metrics.visionPerMinute },
            averageKillParticipation = meanOf { it.metrics.killParticipation },
            averageDamageShare = meanOf { it.metrics.damageShare },
        )

    private fun PlayerScopeStatisticsValues.toPositionStatistics(position: String): PlayerPositionStatistics =
        PlayerPositionStatistics(
            position = position,
            games = games,
            wins = wins,
            winRate = winRate,
            averageKda = averageKda,
            averageCsPerMinute = averageCsPerMinute,
            averageGoldPerMinute = averageGoldPerMinute,
            averageDamagePerMinute = averageDamagePerMinute,
            averageVisionPerMinute = averageVisionPerMinute,
            averageKillParticipation = averageKillParticipation,
            averageDamageShare = averageDamageShare,
        )

    private fun PlayerScopeStatisticsValues.toChampionPositionStatistics(cohort: ChampionPositionKey): PlayerChampionPositionStatistics =
        PlayerChampionPositionStatistics(
            championId = cohort.championId,
            position = cohort.position,
            games = games,
            wins = wins,
            winRate = winRate,
            averageKda = averageKda,
            averageCsPerMinute = averageCsPerMinute,
            averageGoldPerMinute = averageGoldPerMinute,
            averageDamagePerMinute = averageDamagePerMinute,
            averageVisionPerMinute = averageVisionPerMinute,
            averageKillParticipation = averageKillParticipation,
            averageDamageShare = averageDamageShare,
        )

    private data class CohortObservation(
        val participant: MatchParticipant,
        val metrics: MatchParticipantMetrics,
    )

    private data class ChampionPositionKey(
        val championId: Int,
        val position: String,
    )

    private data class PlayerScopeStatisticsValues(
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
    )
}
