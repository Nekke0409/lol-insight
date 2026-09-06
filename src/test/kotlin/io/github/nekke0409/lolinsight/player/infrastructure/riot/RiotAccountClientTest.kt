package io.github.nekke0409.lolinsight.player.infrastructure.riot

import io.github.nekke0409.lolinsight.global.riot.RiotApiHttpClient
import io.github.nekke0409.lolinsight.global.riot.RiotApiProperties
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiotAccountClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: RiotAccountClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()

        val properties =
            RiotApiProperties(
                key = "test-api-key",
                platformBaseUrl = java.net.URI.create("https://platform.test"),
                regionalBaseUrl = java.net.URI.create("https://regional.test"),
            )
        client = RiotAccountClient(RiotApiHttpClient(builder.build(), properties))
    }

    @Test
    fun `finds an account by Riot ID using Asia regional routing`() {
        server
            .expect(
                requestTo(
                    "https://regional.test/riot/account/v1/accounts/by-riot-id/Hide%20on%20bush/KR1",
                ),
            ).andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"puuid\":\"test-puuid\",\"gameName\":\"Hide on bush\",\"tagLine\":\"KR1\"}"),
            )

        val response = client.findByRiotId(gameName = "Hide on bush", tagLine = "KR1")

        assertEquals("test-puuid", response.puuid)
        assertEquals("Hide on bush", response.gameName)
        assertEquals("KR1", response.tagLine)
        server.verify()
    }

    @Test
    fun `converts Account API error responses to Riot API response exceptions`() {
        server
            .expect(requestTo("https://regional.test/riot/account/v1/accounts/by-riot-id/unknown/KR1"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":{\"message\":\"Data not found\",\"status_code\":404}}"),
            )

        val exception =
            assertFailsWith<RiotApiResponseException> {
                client.findByRiotId(gameName = "unknown", tagLine = "KR1")
            }

        assertEquals(404, exception.statusCode.value())
        assertEquals("{\"status\":{\"message\":\"Data not found\",\"status_code\":404}}", exception.responseBody)
        server.verify()
    }
}
