package io.github.nekke0409.lolinsight.agent.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseOutputItem
import com.openai.services.blocking.ResponseService
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.OpenAiProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiAgentModelGatewayTest {
    @Test
    fun `creates two strict sequential function tools with bounded Responses settings`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mock(Response::class.java)
        val output = mock(ResponseOutputItem::class.java)
        val functionCall = mock(ResponseFunctionToolCall::class.java)
        var captured: ResponseCreateParams? = null
        `when`(client.responses()).thenReturn(responses)
        `when`(response.output()).thenReturn(listOf(output))
        `when`(output.isFunctionCall()).thenReturn(true)
        `when`(output.asFunctionCall()).thenReturn(functionCall)
        `when`(functionCall.callId()).thenReturn("call-ranked-stats")
        `when`(functionCall.name()).thenReturn("get_ranked_stats")
        `when`(functionCall.arguments()).thenReturn("{\"groupBy\":\"POSITION\"}")
        `when`(responses.create(anyResponseCreateParams(), anyRequestOptions())).thenAnswer { invocation ->
            captured = invocation.getArgument(0)
            response
        }

        val turn =
            OpenAiAgentModelGateway(
                openAiProperties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
                agentProperties = AgentQuestionProperties(enabled = true, maxOutputTokens = 321),
                clientOverride = client,
            ).start("미드 통계", java.time.Duration.ofSeconds(5))

        val params = requireNotNull(captured)
        assertFalse(params.store().orElseThrow())
        assertFalse(params.parallelToolCalls().orElseThrow())
        assertEquals(321, params.maxOutputTokens().orElseThrow())
        assertEquals(2, params.tools().orElseThrow().size)
        params.tools().orElseThrow().forEach { tool ->
            assertTrue(tool.isFunction())
            assertTrue(tool.asFunction().strict().orElseThrow())
            val parameters =
                tool
                    .asFunction()
                    .parameters()
                    .orElseThrow()
                    ._additionalProperties()
            assertEquals(false, parameters.getValue("additionalProperties").convert(Boolean::class.java))
        }
        assertEquals("call-ranked-stats", turn.toolCalls.single().callId)
        assertEquals("get_ranked_stats", turn.toolCalls.single().name)
    }

    private fun anyResponseCreateParams(): ResponseCreateParams {
        any(ResponseCreateParams::class.java)
        return ResponseCreateParams
            .builder()
            .model("gpt-5-mini")
            .input("{}")
            .build()
    }

    private fun anyRequestOptions(): RequestOptions {
        any(RequestOptions::class.java)
        return RequestOptions.none()
    }
}
