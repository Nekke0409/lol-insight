package io.github.nekke0409.lolinsight.match.application

import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchClient
import org.springframework.stereotype.Service

@Service
class MatchService(
    private val riotMatchClient: RiotMatchClient,
) {
    fun findByMatchId(matchId: String): MatchResponse = MatchResponse.from(riotMatchClient.findMatchById(matchId))
}
