package io.github.nekke0409.lolinsight.agent.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.ToolChoiceOptions
import com.openai.services.blocking.ResponseService
import io.github.nekke0409.lolinsight.agent.application.AgentModelIncompleteResponseException
import io.github.nekke0409.lolinsight.agent.application.AgentModelRefusalException
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolOutput
import io.github.nekke0409.lolinsight.agent.application.AgentModelUsage
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.OpenAiProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val captured = mutableListOf<ResponseCreateParams>()
        val reasoningOutput = mock(ResponseOutputItem::class.java)
        val reasoning = mock(ResponseReasoningItem::class.java)
        `when`(client.responses()).thenReturn(responses)
        `when`(response.output()).thenReturn(listOf(reasoningOutput, output))
        `when`(response.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED))
        `when`(response.error()).thenReturn(Optional.empty())
        `when`(response.usage()).thenReturn(Optional.empty())
        `when`(reasoningOutput.isReasoning()).thenReturn(true)
        `when`(reasoningOutput.asReasoning()).thenReturn(reasoning)
        `when`(output.isFunctionCall()).thenReturn(true)
        `when`(output.asFunctionCall()).thenReturn(functionCall)
        `when`(functionCall.callId()).thenReturn("call-ranked-stats")
        `when`(functionCall.name()).thenReturn("get_ranked_stats")
        `when`(functionCall.arguments()).thenReturn("{\"groupBy\":\"POSITION\"}")
        `when`(responses.create(anyResponseCreateParams(), anyRequestOptions())).thenAnswer { invocation ->
            captured += invocation.getArgument<ResponseCreateParams>(0)
            response
        }

        val gateway =
            OpenAiAgentModelGateway(
                openAiProperties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
                agentProperties = AgentQuestionProperties(enabled = true, maxOutputTokens = 321),
                clientOverride = client,
            )
        val turn = gateway.start("미드 통계", allowToolCalls = true, timeout = java.time.Duration.ofSeconds(5))
        gateway.continueWithToolOutputs(
            continuation = turn.continuation,
            outputs = listOf(AgentModelToolOutput("call-ranked-stats", "{\"status\":\"AVAILABLE\"}")),
            allowToolCalls = false,
            timeout = java.time.Duration.ofSeconds(5),
        )

        val params = captured.first()
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

        val continuationParams = captured.last()
        assertEquals(ToolChoiceOptions.NONE, continuationParams.toolChoice().orElseThrow().asOptions())
        val continuationInput = continuationParams.input().orElseThrow().asResponse()
        assertEquals(4, continuationInput.size)
        assertTrue(continuationInput[1].isReasoning())
        assertTrue(continuationInput[2].isFunctionCall())
        assertTrue(continuationInput[3].isFunctionCallOutput())
        assertEquals("call-ranked-stats", continuationInput[3].asFunctionCallOutput().callId().orElseThrow())
    }

    @Test
    fun `preserves provider usage when an incomplete Responses output is rejected before exposing a Tool call`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mock(Response::class.java)
        val responseUsage = usage(101, 202, 303)
        `when`(client.responses()).thenReturn(responses)
        `when`(response.status()).thenReturn(Optional.of(ResponseStatus.INCOMPLETE))
        `when`(response.error()).thenReturn(Optional.empty())
        `when`(response.incompleteDetails()).thenReturn(Optional.empty())
        `when`(response.usage()).thenReturn(Optional.of(responseUsage))
        `when`(responses.create(anyResponseCreateParams(), anyRequestOptions())).thenReturn(response)

        val gateway =
            OpenAiAgentModelGateway(
                openAiProperties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
                agentProperties = AgentQuestionProperties(enabled = true),
                clientOverride = client,
            )

        val exception =
            assertFailsWith<AgentModelIncompleteResponseException> {
                gateway.start("미드 통계", allowToolCalls = true, timeout = java.time.Duration.ofSeconds(5))
            }

        assertEquals(AgentModelUsage(101, 202, 303), exception.usage)
    }

    @Test
    fun `preserves provider usage when a completed Responses output contains a refusal`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mock(Response::class.java)
        val output = mock(ResponseOutputItem::class.java)
        val message = mock(ResponseOutputMessage::class.java)
        val content = mock(ResponseOutputMessage.Content::class.java)
        val responseUsage = usage(11, 22, 33)
        `when`(client.responses()).thenReturn(responses)
        `when`(response.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED))
        `when`(response.error()).thenReturn(Optional.empty())
        `when`(response.output()).thenReturn(listOf(output))
        `when`(response.usage()).thenReturn(Optional.of(responseUsage))
        `when`(output.isMessage()).thenReturn(true)
        `when`(output.asMessage()).thenReturn(message)
        `when`(message.content()).thenReturn(listOf(content))
        `when`(content.isRefusal()).thenReturn(true)
        `when`(responses.create(anyResponseCreateParams(), anyRequestOptions())).thenReturn(response)

        val gateway =
            OpenAiAgentModelGateway(
                openAiProperties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
                agentProperties = AgentQuestionProperties(enabled = true),
                clientOverride = client,
            )

        val exception =
            assertFailsWith<AgentModelRefusalException> {
                gateway.start("미드 통계", allowToolCalls = true, timeout = java.time.Duration.ofSeconds(5))
            }

        assertEquals(AgentModelUsage(11, 22, 33), exception.usage)
    }

    @Test
    fun `does not synthesize usage when an incomplete Responses output has no usage`() {
        val client = mock(OpenAIClient::class.java)
        val responses = mock(ResponseService::class.java)
        val response = mock(Response::class.java)
        `when`(client.responses()).thenReturn(responses)
        `when`(response.status()).thenReturn(Optional.of(ResponseStatus.INCOMPLETE))
        `when`(response.error()).thenReturn(Optional.empty())
        `when`(response.incompleteDetails()).thenReturn(Optional.empty())
        `when`(response.usage()).thenReturn(Optional.empty())
        `when`(responses.create(anyResponseCreateParams(), anyRequestOptions())).thenReturn(response)

        val gateway =
            OpenAiAgentModelGateway(
                openAiProperties = OpenAiProperties(apiKey = "test-key", model = "gpt-5-mini"),
                agentProperties = AgentQuestionProperties(enabled = true),
                clientOverride = client,
            )

        val exception =
            assertFailsWith<AgentModelIncompleteResponseException> {
                gateway.start("미드 통계", allowToolCalls = true, timeout = java.time.Duration.ofSeconds(5))
            }

        assertEquals(null, exception.usage)
    }

    private fun usage(
        inputTokens: Long,
        outputTokens: Long,
        totalTokens: Long,
    ): ResponseUsage =
        mock(ResponseUsage::class.java).also {
            `when`(it.inputTokens()).thenReturn(inputTokens)
            `when`(it.outputTokens()).thenReturn(outputTokens)
            `when`(it.totalTokens()).thenReturn(totalTokens)
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
