package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.core.http.Headers
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.responses.StructuredResponse
import com.openai.models.responses.StructuredResponseCreateParams
import com.openai.models.responses.StructuredResponseOutputItem
import com.openai.models.responses.StructuredResponseOutputMessage
import com.openai.services.blocking.ResponseService
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisAuthenticationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisConfigurationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInvalidResponseException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisTransportException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiPlayerAnalysisGeneratorTest {
    private val promptFactory = PlayerAnalysisPromptFactory(JsonMapper.builder().build())

    @Test
    fun `converts structured OpenAI output to the provider independent result`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mockStructuredResponse(output())
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)

        val result = generator(client).generate(TestPlayerAnalysisInput.input())

        assertEquals("요약", result.summary)
        assertEquals("CS/min 비교", result.observations.single().title)
        assertEquals("player=7.2", result.observations.single().evidence)
        assertEquals(emptyList(), result.strengths)
    }

    @Test
    fun `maps missing API configuration without constructing an OpenAI request`() {
        val generator =
            OpenAiPlayerAnalysisGenerator(
                properties = OpenAiProperties(apiKey = "", model = "gpt-5-mini"),
                promptFactory = promptFactory,
            )

        assertFailsWith<PlayerAnalysisConfigurationException> {
            generator.generate(TestPlayerAnalysisInput.input())
        }
    }

    @Test
    fun `maps OpenAI auth rate limit provider and transport failures`() {
        assertFailsWith<PlayerAnalysisAuthenticationException> {
            generatorThrowing(UnauthorizedException.builder().headers(Headers.builder().build()).build())
                .generate(TestPlayerAnalysisInput.input())
        }
        assertFailsWith<PlayerAnalysisRateLimitException> {
            generatorThrowing(RateLimitException.builder().headers(Headers.builder().build()).build())
                .generate(TestPlayerAnalysisInput.input())
        }
        assertFailsWith<PlayerAnalysisProviderException> {
            generatorThrowing(
                InternalServerException
                    .builder()
                    .statusCode(500)
                    .headers(Headers.builder().build())
                    .build(),
            ).generate(TestPlayerAnalysisInput.input())
        }
        assertFailsWith<PlayerAnalysisTransportException> {
            generatorThrowing(OpenAIIoException("connect timed out")).generate(TestPlayerAnalysisInput.input())
        }
    }

    @Test
    fun `maps an empty structured response to an invalid response error`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)

        @Suppress("UNCHECKED_CAST")
        val response = mock(StructuredResponse::class.java) as StructuredResponse<OpenAiPlayerAnalysisOutput>
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)
        `when`(response.output()).thenReturn(emptyList())

        assertFailsWith<PlayerAnalysisInvalidResponseException> {
            generator(client).generate(TestPlayerAnalysisInput.input())
        }
    }

    private fun generator(client: OpenAIClient): OpenAiPlayerAnalysisGenerator =
        OpenAiPlayerAnalysisGenerator(
            properties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
            promptFactory = promptFactory,
            clientOverride = client,
        )

    private fun generatorThrowing(exception: RuntimeException): OpenAiPlayerAnalysisGenerator {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        `when`(client.responses()).thenReturn(responses)
        doThrow(exception).`when`(responses).create(anyStructuredResponseParams())
        return generator(client)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyStructuredResponseParams(): StructuredResponseCreateParams<OpenAiPlayerAnalysisOutput> {
        any(StructuredResponseCreateParams::class.java)
        return StructuredResponseCreateParams
            .builder<OpenAiPlayerAnalysisOutput>()
            .model("gpt-5-mini")
            .input("{}")
            .text(OpenAiPlayerAnalysisOutput::class.java)
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockStructuredResponse(output: OpenAiPlayerAnalysisOutput): StructuredResponse<OpenAiPlayerAnalysisOutput> {
        val response = mock(StructuredResponse::class.java) as StructuredResponse<OpenAiPlayerAnalysisOutput>
        val outputItem =
            mock(StructuredResponseOutputItem::class.java) as StructuredResponseOutputItem<OpenAiPlayerAnalysisOutput>
        val message =
            mock(StructuredResponseOutputMessage::class.java) as StructuredResponseOutputMessage<OpenAiPlayerAnalysisOutput>
        val content =
            mock(StructuredResponseOutputMessage.Content::class.java) as
                StructuredResponseOutputMessage.Content<OpenAiPlayerAnalysisOutput>
        `when`(response.output()).thenReturn(listOf(outputItem))
        `when`(outputItem.message()).thenReturn(Optional.of(message))
        `when`(message.content()).thenReturn(listOf(content))
        `when`(content.outputText()).thenReturn(Optional.of(output))
        return response
    }

    private fun output(): OpenAiPlayerAnalysisOutput =
        OpenAiPlayerAnalysisOutput().apply {
            summary = "요약"
            observations =
                listOf(
                    OpenAiAnalysisInsightOutput().apply {
                        title = "CS/min 비교"
                        explanation = "중앙값보다 높습니다."
                        evidence = "player=7.2"
                    },
                )
            strengths = emptyList()
            focusAreas = emptyList()
            caveats = emptyList()
        }
}
