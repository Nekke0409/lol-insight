package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.core.http.Headers
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.ResponsesModel
import com.openai.models.responses.ResponseUsage
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiPlayerAnalysisGeneratorTest {
    private val promptFactory = PlayerAnalysisPromptFactory(JsonMapper.builder().build())

    @Test
    fun `converts structured OpenAI output to the provider independent result and records usage`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val observationRecorder = RecordingObservationRecorder()
        val response = mockStructuredResponse(output(), usage())
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)

        val result = generator(client, observationRecorder).generate(TestPlayerAnalysisInput.input())

        assertEquals("summary", result.summary)
        assertEquals("CS/min comparison", result.observations.single().title)
        assertEquals("player=7.2", result.observations.single().evidence)
        assertEquals(emptyList(), result.strengths)
        assertEquals("gpt-5-mini-2026-09-01", observationRecorder.successes.single().model)
        assertEquals(OpenAiTokenUsage(101, 202, 303), observationRecorder.successes.single().usage)
        val success = observationRecorder.successes.single()
        assertTrue(success.latency.isPositive)
        verify(responses).create(anyStructuredResponseParams())
    }

    @Test
    fun `keeps analysis successful when the OpenAI response omits usage`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val observationRecorder = RecordingObservationRecorder()
        val response = mockStructuredResponse(output())
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)

        val result = generator(client, observationRecorder).generate(TestPlayerAnalysisInput.input())

        assertEquals("summary", result.summary)
        assertNull(observationRecorder.successes.single().usage)
    }

    @Test
    fun `maps missing API configuration without constructing an OpenAI request`() {
        val observationRecorder = RecordingObservationRecorder()
        val generator =
            OpenAiPlayerAnalysisGenerator(
                properties = OpenAiProperties(apiKey = "", model = "gpt-5-mini"),
                promptFactory = promptFactory,
                observationRecorder = observationRecorder,
            )

        assertFailsWith<PlayerAnalysisConfigurationException> {
            generator.generate(TestPlayerAnalysisInput.input())
        }

        assertEquals(OpenAiAnalysisFailureCategory.CONFIGURATION, observationRecorder.failures.single().category)
        assertNull(observationRecorder.failures.single().latency)
    }

    @Test
    fun `maps OpenAI auth rate limit provider and transport failures without changing their semantics`() {
        val authRecorder = RecordingObservationRecorder()
        assertFailsWith<PlayerAnalysisAuthenticationException> {
            generatorThrowing(UnauthorizedException.builder().headers(Headers.builder().build()).build(), authRecorder)
                .generate(TestPlayerAnalysisInput.input())
        }
        assertEquals(OpenAiAnalysisFailureCategory.AUTHENTICATION_PERMISSION, authRecorder.failures.single().category)

        val rateLimitRecorder = RecordingObservationRecorder()
        assertFailsWith<PlayerAnalysisRateLimitException> {
            generatorThrowing(RateLimitException.builder().headers(Headers.builder().build()).build(), rateLimitRecorder)
                .generate(TestPlayerAnalysisInput.input())
        }
        assertEquals(OpenAiAnalysisFailureCategory.RATE_LIMIT, rateLimitRecorder.failures.single().category)

        assertFailsWith<PlayerAnalysisProviderException> {
            generatorThrowing(
                InternalServerException
                    .builder()
                    .statusCode(500)
                    .headers(Headers.builder().build())
                    .build(),
            ).generate(TestPlayerAnalysisInput.input())
        }

        val transportRecorder = RecordingObservationRecorder()
        assertFailsWith<PlayerAnalysisTransportException> {
            generatorThrowing(OpenAIIoException("connect timed out"), transportRecorder)
                .generate(TestPlayerAnalysisInput.input())
        }
        assertEquals(OpenAiAnalysisFailureCategory.TIMEOUT_NETWORK, transportRecorder.failures.single().category)
        val transportFailure = transportRecorder.failures.single()
        assertTrue(transportFailure.latency!!.isPositive)
    }

    @Test
    fun `maps an empty structured response to an invalid response error and records the failure`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val observationRecorder = RecordingObservationRecorder()

        @Suppress("UNCHECKED_CAST")
        val response = mock(StructuredResponse::class.java) as StructuredResponse<OpenAiPlayerAnalysisOutput>
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)
        `when`(response.output()).thenReturn(emptyList())

        assertFailsWith<PlayerAnalysisInvalidResponseException> {
            generator(client, observationRecorder).generate(TestPlayerAnalysisInput.input())
        }

        assertEquals(OpenAiAnalysisFailureCategory.MALFORMED_STRUCTURED_OUTPUT, observationRecorder.failures.single().category)
        val malformedOutputFailure = observationRecorder.failures.single()
        assertTrue(malformedOutputFailure.latency!!.isPositive)
    }

    @Test
    fun `does not let an observability failure change a successful analysis`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mockStructuredResponse(output(), usage())
        `when`(client.responses()).thenReturn(responses)
        `when`(responses.create(anyStructuredResponseParams())).thenReturn(response)

        val result = generator(client, ThrowingObservationRecorder).generate(TestPlayerAnalysisInput.input())

        assertEquals("summary", result.summary)
    }

    private fun generator(
        client: OpenAIClient,
        observationRecorder: OpenAiAnalysisObservationRecorder = NoOpOpenAiAnalysisObservationRecorder,
    ): OpenAiPlayerAnalysisGenerator =
        OpenAiPlayerAnalysisGenerator(
            properties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
            promptFactory = promptFactory,
            clientOverride = client,
            observationRecorder = observationRecorder,
        )

    private fun generatorThrowing(
        exception: RuntimeException,
        observationRecorder: OpenAiAnalysisObservationRecorder = NoOpOpenAiAnalysisObservationRecorder,
    ): OpenAiPlayerAnalysisGenerator {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        `when`(client.responses()).thenReturn(responses)
        doThrow(exception).`when`(responses).create(anyStructuredResponseParams())
        return generator(client, observationRecorder)
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
    private fun mockStructuredResponse(
        output: OpenAiPlayerAnalysisOutput,
        usage: ResponseUsage? = null,
    ): StructuredResponse<OpenAiPlayerAnalysisOutput> {
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
        `when`(response.model()).thenReturn(ResponsesModel.ofString("gpt-5-mini-2026-09-01"))
        `when`(response.usage()).thenReturn(Optional.ofNullable(usage))
        return response
    }

    private fun usage(): ResponseUsage {
        val usage = mock(ResponseUsage::class.java)
        `when`(usage.inputTokens()).thenReturn(101)
        `when`(usage.outputTokens()).thenReturn(202)
        `when`(usage.totalTokens()).thenReturn(303)
        return usage
    }

    private fun output(): OpenAiPlayerAnalysisOutput =
        OpenAiPlayerAnalysisOutput().apply {
            summary = "summary"
            observations =
                listOf(
                    OpenAiAnalysisInsightOutput().apply {
                        title = "CS/min comparison"
                        explanation = "The player's CS/min is above the benchmark median."
                        evidence = "player=7.2"
                    },
                )
            strengths = emptyList()
            focusAreas = emptyList()
            caveats = emptyList()
        }

    private class RecordingObservationRecorder : OpenAiAnalysisObservationRecorder {
        val successes = mutableListOf<Success>()
        val failures = mutableListOf<Failure>()

        override fun recordSuccess(
            model: String,
            latency: Duration,
            usage: OpenAiTokenUsage?,
        ) {
            successes += Success(model, latency, usage)
        }

        override fun recordFailure(
            model: String,
            category: OpenAiAnalysisFailureCategory,
            latency: Duration?,
        ) {
            failures += Failure(model, category, latency)
        }
    }

    private data class Success(
        val model: String,
        val latency: Duration,
        val usage: OpenAiTokenUsage?,
    )

    private data class Failure(
        val model: String,
        val category: OpenAiAnalysisFailureCategory,
        val latency: Duration?,
    )

    private object ThrowingObservationRecorder : OpenAiAnalysisObservationRecorder {
        override fun recordSuccess(
            model: String,
            latency: Duration,
            usage: OpenAiTokenUsage?,
        ) = throw IllegalStateException("metrics unavailable")

        override fun recordFailure(
            model: String,
            category: OpenAiAnalysisFailureCategory,
            latency: Duration?,
        ) = throw IllegalStateException("metrics unavailable")
    }
}
