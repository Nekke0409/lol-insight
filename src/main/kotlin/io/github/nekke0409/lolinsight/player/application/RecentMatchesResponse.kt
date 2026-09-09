package io.github.nekke0409.lolinsight.player.application

import io.github.nekke0409.lolinsight.match.domain.Match
import io.github.nekke0409.lolinsight.match.domain.MatchParticipant
import java.time.Instant

data class RecentMatchesResponse(
    val player: RecentMatchesPlayerResponse,
    val page: RecentMatchesPageResponse,
    val matches: List<MatchSummaryResponse>,
)

data class RecentMatchesPlayerResponse(
    val gameName: String,
    val tagLine: String,
)

data class RecentMatchesPageResponse(
    val start: Int,
    val requestedCount: Int,
    val sourceMatchCount: Int,
    val returnedCount: Int,
    val partial: Boolean,
    val unavailableCount: Int,
)

data class MatchSummaryResponse(
    val matchId: String,
    val queueId: Int,
    val gameMode: String,
    val startedAt: Instant,
    val durationSeconds: Long,
    val participant: MatchParticipantSummaryResponse,
) {
    companion object {
        fun from(
            match: Match,
            participant: MatchParticipant,
        ): MatchSummaryResponse =
            MatchSummaryResponse(
                matchId = match.matchId,
                queueId = match.queueId,
                gameMode = match.gameMode,
                startedAt = match.startedAt,
                durationSeconds = match.duration.seconds,
                participant = MatchParticipantSummaryResponse.from(participant),
            )
    }
}

data class MatchParticipantSummaryResponse(
    val championId: Int,
    val championName: String,
    val position: String,
    val win: Boolean,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val totalCs: Int,
    val itemIds: List<Int>,
) {
    companion object {
        fun from(participant: MatchParticipant): MatchParticipantSummaryResponse =
            MatchParticipantSummaryResponse(
                championId = participant.champion.id,
                championName = participant.champion.name,
                position = participant.position,
                win = participant.won,
                kills = participant.kills,
                deaths = participant.deaths,
                assists = participant.assists,
                totalCs = participant.laneMinionKills + participant.neutralMinionKills,
                itemIds = participant.itemIdsBySlot,
            )
    }
}
