package io.github.nekke0409.lolinsight.match.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.URI
import kotlin.test.assertEquals

class RiotMatchClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: RiotMatchClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()

        val properties =
            RiotApiProperties(
                key = "test-api-key",
                platformBaseUrl = URI.create("https://kr.api.riotgames.com"),
                regionalBaseUrl = URI.create("https://asia.api.riotgames.com"),
            )
        client = RiotMatchClient(RiotApiHttpClient(builder.build(), properties))
    }

    @Test
    fun `finds Match IDs with PUUID and pagination parameters using Asia regional routing`() {
        server
            .expect(
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/test-puuid/ids?start=40&count=10",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[\"KR_100\",\"KR_99\"]"),
            )

        val matchIds = client.findMatchIdsByPuuid(puuid = "test-puuid", start = 40, count = 10)

        assertEquals(listOf("KR_100", "KR_99"), matchIds)
        server.verify()
    }

    @Test
    fun `returns an empty list when no Match IDs are available`() {
        server
            .expect(
                requestTo(
                    "https://asia.api.riotgames.com/lol/match/v5/matches/by-puuid/test-puuid/ids?start=0&count=20",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[]"),
            )

        val matchIds = client.findMatchIdsByPuuid(puuid = "test-puuid")

        assertEquals(emptyList(), matchIds)
        server.verify()
    }
}
