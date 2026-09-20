package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentQuestionServiceTest {
    private val rateLimiter = mock(AnalysisGenerationRateLimiter::class.java)

    @Test
    fun `uses scripted model tool call dispatcher result and final answer`() {
        val model =
            ScriptedAgentModelGateway(
                turn(call = toolCall("call-stats", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}")),
                turn(text = "최근 MID Ranked Solo 통계입니다."),
            )
        val dispatcher = RecordingToolExecutor()

        val response = service(model, dispatcher).answer("Hide on bush", "KR1", "미드 최근 통계를 알려줘", clientKey())

        assertEquals("최근 MID Ranked Solo 통계입니다.", response.answer)
        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertEquals(listOf(AgentUsedTool("get_ranked_stats", true, 1)), response.usedTools)
        assertEquals(1, dispatcher.calls.size)
        assertEquals(1, model.startCalls)
        assertEquals(1, model.continueCalls)
        verify(rateLimiter).check(clientKey())
    }

    @Test
    fun `allows two different sequential tools and no more`() {
        val model =
            ScriptedAgentModelGateway(
                turn(call = toolCall("call-stats", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}")),
                turn(call = toolCall("call-peer", "get_peer_comparison", "{\"groupBy\":\"POSITION\"}")),
                turn(text = "통계와 비교 결과를 함께 정리했습니다."),
            )
        val dispatcher = RecordingToolExecutor()

        val response = service(model, dispatcher).answer("Hide on bush", "KR1", "미드 비교도 알려줘", clientKey())

        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertEquals(2, response.usedTools.size)
        assertEquals(2, model.continueCalls)
        assertEquals(2, dispatcher.calls.size)
    }

    @Test
    fun `blocks multiple calls in one model turn without tool execution`() {
        val model =
            ScriptedAgentModelGateway(
                AgentModelTurn(
                    text = null,
                    toolCalls =
                        listOf(
                            toolCall("call-1", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}"),
                            toolCall("call-2", "get_peer_comparison", "{\"groupBy\":\"POSITION\"}"),
                        ),
                    continuation = TestContinuation,
                ),
            )
        val dispatcher = RecordingToolExecutor()

        val response = service(model, dispatcher).answer("Hide on bush", "KR1", "비교해줘", clientKey())

        assertEquals(AgentTerminationReason.PARALLEL_TOOL_CALL_REJECTED, response.terminationReason)
        assertTrue(response.dataLimitations.single().contains("하나"))
        assertTrue(dispatcher.calls.isEmpty())
    }

    @Test
    fun `blocks duplicate calls while preserving the bounded final turn`() {
        val repeated = toolCall("call-2", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}")
        val model =
            ScriptedAgentModelGateway(
                turn(call = toolCall("call-1", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}")),
                turn(call = repeated),
                turn(text = "동일한 통계 요청은 한 번만 실행했습니다."),
            )
        val dispatcher = RecordingToolExecutor()

        val response = service(model, dispatcher).answer("Hide on bush", "KR1", "미드 통계를 다시 알려줘", clientKey())

        assertEquals(AgentTerminationReason.COMPLETED, response.terminationReason)
        assertTrue(
            response.usedTools
                .last()
                .success
                .not(),
        )
        assertTrue(response.dataLimitations.any { it.contains("반복") })
        assertEquals(1, dispatcher.calls.size)
    }

    @Test
    fun `disables Tool calls on the final allowed model request`() {
        val model =
            ScriptedAgentModelGateway(
                turn(call = toolCall("call-1", "get_ranked_stats", "{\"groupBy\":\"POSITION\"}")),
                turn(call = toolCall("call-2", "get_peer_comparison", "{\"groupBy\":\"POSITION\"}")),
            )
        val dispatcher = RecordingToolExecutor()

        val response =
            service(
                model,
                dispatcher,
                AgentQuestionProperties(enabled = true, maxModelRequests = 2, maxToolExecutions = 2),
            ).answer("Hide on bush", "KR1", "통계와 비교", clientKey())

        assertEquals(AgentTerminationReason.TOOL_CALL_NOT_ALLOWED, response.terminationReason)
        assertEquals(listOf(true, false), model.allowToolCalls)
        assertEquals(1, dispatcher.calls.size)
    }

    @Test
    fun `keeps agent disabled before consuming a request quota or calling the model`() {
        val model = ScriptedAgentModelGateway(turn(text = "should not happen"))
        val responseService = service(model, RecordingToolExecutor(), AgentQuestionProperties(enabled = false))

        kotlin.test.assertFailsWith<AgentFeatureDisabledException> {
            responseService.answer("Hide on bush", "KR1", "미드 통계", clientKey())
        }

        org.mockito.Mockito.verifyNoInteractions(rateLimiter)
        assertEquals(0, model.startCalls)
    }

    @Test
    fun `records bounded execution metadata and only provider supplied token usage`() {
        val model = ScriptedAgentModelGateway(turn(text = "최근 통계입니다.", usage = AgentModelUsage(10, 20, 30)))
        val recorder = RecordingObservationRecorder()
        val service =
            AgentQuestionService(
                AgentQuestionProperties(enabled = true),
                rateLimiter,
                model,
                RecordingToolExecutor(),
                DirectToolExecutionRunner,
                recorder,
            )

        service.answer("Hide on bush", "KR1", "최근 미드 통계", clientKey())

        assertEquals(1, recorder.summary.modelRequestAttempts)
        assertEquals(0, recorder.summary.toolExecutionAttempts)
        assertEquals(AgentTerminationReason.COMPLETED, recorder.summary.terminationReason)
        assertEquals(listOf(AgentModelUsage(10, 20, 30)), recorder.summary.usage)
        assertTrue(recorder.summary.usedTools.isEmpty())
    }

    private fun service(
        model: AgentModelGateway,
        dispatcher: AgentToolExecutor = RecordingToolExecutor(),
        properties: AgentQuestionProperties = AgentQuestionProperties(enabled = true),
    ): AgentQuestionService = AgentQuestionService(properties, rateLimiter, model, dispatcher, DirectToolExecutionRunner)

    private fun clientKey(): AnalysisRateLimitKey = AnalysisRateLimitKey("127.0.0.1")

    private fun turn(
        text: String? = null,
        call: AgentModelToolCall? = null,
        usage: AgentModelUsage? = null,
    ): AgentModelTurn = AgentModelTurn(text, listOfNotNull(call), TestContinuation, usage)

    private fun toolCall(
        callId: String,
        name: String,
        arguments: String,
    ): AgentModelToolCall = AgentModelToolCall(callId, name, arguments)

    private object TestContinuation : AgentModelContinuation

    private object DirectToolExecutionRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: Duration,
            action: () -> T,
        ): T = action()
    }

    private class RecordingObservationRecorder : AgentQuestionObservationRecorder {
        lateinit var summary: AgentQuestionExecutionSummary

        override fun record(summary: AgentQuestionExecutionSummary) {
            this.summary = summary
        }
    }

    private class RecordingToolExecutor : AgentToolExecutor {
        val calls = mutableListOf<AgentModelToolCall>()

        override fun newContext(
            gameName: String,
            tagLine: String,
        ): AgentToolExecutionContext = AgentToolExecutionContext(gameName, tagLine, mock(PlayerComparisonContextService::class.java))

        override fun dispatch(
            call: AgentModelToolCall,
            context: AgentToolExecutionContext,
        ): AgentToolDispatchResult {
            calls += call
            return AgentToolDispatchResult.succeeded(call.name, mapOf("status" to "AVAILABLE"), emptyList())
        }

        override fun serialize(result: AgentToolDispatchResult): String = "{\"status\":\"AVAILABLE\"}"

        override fun callSignature(call: AgentModelToolCall): String = call.name + '\u0000' + call.arguments
    }

    private class ScriptedAgentModelGateway(
        vararg scriptedTurns: AgentModelTurn,
    ) : AgentModelGateway {
        private val turns = ArrayDeque(scriptedTurns.toList())
        var startCalls = 0
        var continueCalls = 0
        val allowToolCalls = mutableListOf<Boolean>()

        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            startCalls++
            this.allowToolCalls += allowToolCalls
            return next()
        }

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            continueCalls++
            this.allowToolCalls += allowToolCalls
            return next()
        }

        private fun next(): AgentModelTurn = turns.removeFirst()
    }
}
