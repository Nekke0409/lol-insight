package io.github.nekke0409.lolinsight.benchmark.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.rank.application.CurrentRankedSoloRank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiotLeagueClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: RiotLeagueClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client =
            RiotLeagueClient(
                RiotApiHttpClient(
                    restClient = builder.build(),
                    properties =
                        RiotApiProperties(
                            key = "test-api-key",
                            platformBaseUrl = URI.create("https://kr.api.riotgames.com"),
                            regionalBaseUrl = URI.create("https://asia.api.riotgames.com"),
                        ),
                    cooldown =
                        RiotApiCooldown(
                            RiotApiProperties(key = "test-api-key"),
                            Clock.systemUTC(),
                        ),
                ),
            )
    }

    @Test
    fun `finds PUUIDs directly by passing queue tier division and first page to League API`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=1",
                ),
            ).andRespond(jsonResponse("[{\"puuid\":\"league-puuid\"}]"))

        val puuids = client.findRankedPlayerPuuids(tier = "GOLD", division = "I", playerLimit = 1)

        assertEquals(listOf("league-puuid"), puuids)
        server.verify()
    }

    @Test
    fun `continues with later League pages only until the requested player limit`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=1",
                ),
            ).andRespond(jsonResponse("[{\"puuid\":\"puuid-one\"}]"))
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=2",
                ),
            ).andRespond(jsonResponse("[{\"puuid\":\"puuid-two\"}]"))

        val puuids = client.findRankedPlayerPuuids(tier = "GOLD", division = "I", playerLimit = 2)

        assertEquals(listOf("puuid-one", "puuid-two"), puuids)
        server.verify()
    }

    @Test
    fun `returns an empty result when the ranked entry page is not found`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=1",
                ),
            ).andRespond(withStatus(HttpStatus.NOT_FOUND))

        val puuids = client.findRankedPlayerPuuids(tier = "GOLD", division = "I", playerLimit = 10)

        assertEquals(emptyList(), puuids)
        server.verify()
    }

    @Test
    fun `propagates a rate limit response from League API`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=1",
                ),
            ).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"))

        val exception =
            assertFailsWith<RiotApiResponseException> {
                client.findRankedPlayerPuuids(tier = "GOLD", division = "I", playerLimit = 2)
            }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
        assertEquals(3, exception.retryAfterSeconds)
        server.verify()
    }

    @Test
    fun `finds PUUIDs from the explicitly requested League page`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/I?page=3",
                ),
            ).andRespond(jsonResponse("[{\"puuid\":\"page-three-puuid\"}]"))

        val puuids = client.findRankedPlayerPuuidsOnPage(tier = "GOLD", division = "I", page = 3)

        assertEquals(listOf("page-three-puuid"), puuids)
        server.verify()
    }

    @Test
    fun `looks up a player rank by PUUID and selects the Ranked Solo entry instead of Flex`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/by-puuid/player-puuid",
                ),
            ).andRespond(
                jsonResponse(
                    """
                    [
                      {"puuid":"player-puuid","queueType":"RANKED_FLEX_SR","tier":"EMERALD","rank":"II"},
                      {"puuid":"player-puuid","queueType":"RANKED_SOLO_5x5","tier":"GOLD","rank":"I"}
                    ]
                    """.trimIndent(),
                ),
            )

        val rank = client.findCurrentRankedSoloRank("player-puuid")

        assertEquals(CurrentRankedSoloRank(tier = "GOLD", division = "I"), rank)
        server.verify()
    }

    @Test
    fun `returns no rank when a player has no Ranked Solo entry`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/by-puuid/player-puuid",
                ),
            ).andRespond(jsonResponse("[]"))

        val rank = client.findCurrentRankedSoloRank("player-puuid")

        assertEquals(null, rank)
        server.verify()
    }

    @Test
    fun `treats a PUUID rank lookup not found as unranked`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/by-puuid/player-puuid",
                ),
            ).andRespond(withStatus(HttpStatus.NOT_FOUND))

        val rank = client.findCurrentRankedSoloRank("player-puuid")

        assertEquals(null, rank)
        server.verify()
    }

    @Test
    fun `propagates rate limiting from a PUUID rank lookup`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/by-puuid/player-puuid",
                ),
            ).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"))

        val exception =
            assertFailsWith<RiotApiResponseException> {
                client.findCurrentRankedSoloRank("player-puuid")
            }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
        assertEquals(3, exception.retryAfterSeconds)
        server.verify()
    }

    @Test
    fun `propagates a provider server failure from a PUUID rank lookup`() {
        server
            .expect(
                requestTo(
                    "https://kr.api.riotgames.com/lol/league/v4/entries/by-puuid/player-puuid",
                ),
            ).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        val exception =
            assertFailsWith<RiotApiResponseException> {
                client.findCurrentRankedSoloRank("player-puuid")
            }

        assertEquals(HttpStatus.BAD_GATEWAY, exception.statusCode)
        server.verify()
    }

    private fun jsonResponse(body: String) =
        withStatus(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
}
