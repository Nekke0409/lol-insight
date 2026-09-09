package io.github.nekke0409.lolinsight.match.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiRouting
import io.github.nekke0409.lolinsight.match.domain.Match
import org.springframework.stereotype.Component

@Component
class RiotMatchClient(
    private val riotApiHttpClient: RiotApiHttpClient,
) {
    fun findMatchById(matchId: String): Match =
        RiotMatchMapper.toMatch(
            riotApiHttpClient.get(
                routing = RiotApiRouting.REGIONAL,
                path = "/lol/match/v5/matches/{matchId}",
                uriVariables = mapOf("matchId" to matchId),
                responseType = RiotMatchResponseDto::class.java,
            ),
        )

    fun findMatchIdsByPuuid(
        puuid: String,
        start: Int = DEFAULT_START,
        count: Int = DEFAULT_COUNT,
    ): List<String> =
        riotApiHttpClient
            .get(
                routing = RiotApiRouting.REGIONAL,
                path = "/lol/match/v5/matches/by-puuid/{puuid}/ids",
                uriVariables = mapOf("puuid" to puuid),
                queryParameters = mapOf("start" to start, "count" to count),
                responseType = Array<String>::class.java,
            ).toList()

    private companion object {
        const val DEFAULT_START = 0
        const val DEFAULT_COUNT = 20
    }
}
