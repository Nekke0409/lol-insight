package io.github.nekke0409.lolinsight.analysis.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import io.github.nekke0409.lolinsight.player.application.PlayerMatchStatistics
import io.github.nekke0409.lolinsight.player.application.PlayerRecentMatchHistoryPlayer
import io.github.nekke0409.lolinsight.player.application.kda
import org.springframework.stereotype.Component

@Component
class PlayerAnalysisFeatureBuilder {
    fun build(
        player: PlayerRecentMatchHistoryPlayer,
        requestedCount: Int,
        targetPuuid: String,
        matches: List<Match>,
        overall: PlayerMatchStatistics,
    ): PlayerAnalysisFeature {
        val targetParticipants = matches.mapNotNull { it.participants.firstOrNull { participant -> participant.puuid == targetPuuid } }

        return PlayerAnalysisFeature(
            player = PlayerAnalysisFeaturePlayer(player.gameName, player.tagLine),
            sample = PlayerAnalysisFeatureSample(requestedCount = requestedCount, analyzedCount = overall.games),
            overall = overall,
            championStats = targetParticipants.toChampionStats(),
            positionStats = targetParticipants.toPositionStats(),
        )
    }

    private fun List<MatchParticipant>.toChampionStats(): List<PlayerAnalysisChampionStat> =
        groupBy { ChampionKey(it.champion.id, it.champion.name) }
            .map { (champion, participants) ->
                PlayerAnalysisChampionStat(
                    championId = champion.id,
                    championName = champion.name,
                    games = participants.size,
                    wins = participants.count { it.won },
                    winRate = participants.winRate(),
                    averageKda = participants.averageKda(),
                )
            }.sortedWith(
                compareByDescending<PlayerAnalysisChampionStat> { it.games }
                    .thenBy { it.championName }
                    .thenBy { it.championId },
            )

    private fun List<MatchParticipant>.toPositionStats(): List<PlayerAnalysisPositionStat> =
        groupBy { it.position }
            .map { (position, participants) ->
                PlayerAnalysisPositionStat(
                    position = position,
                    games = participants.size,
                    wins = participants.count { it.won },
                    winRate = participants.winRate(),
                    averageKda = participants.averageKda(),
                )
            }.sortedWith(
                compareByDescending<PlayerAnalysisPositionStat> { it.games }.thenBy { it.position },
            )

    private fun List<MatchParticipant>.winRate(): Double = count { it.won }.toDouble() / size

    private fun List<MatchParticipant>.averageKda(): Double = sumOf { it.kda() } / size

    private data class ChampionKey(
        val id: Int,
        val name: String,
    )
}
