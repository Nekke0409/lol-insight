package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentManualSmokeGuardTest {
    @Test
    fun `blocks a player backed Tool before dispatcher entry`() {
        val dispatcher = NeverDispatcher()
        val session = RecordingSession()
        val service =
            AgentQuestionService(
                properties = AgentQuestionProperties(enabled = true),
                rateLimiter = mock(AnalysisGenerationRateLimiter::class.java),
                modelGateway = ToolSelectingModel(),
                toolDispatcher = dispatcher,
                toolExecutionRunner = DirectRunner,
                manualSmokeObserver =
                    object : AgentManualSmokeObserver {
                        override fun admit(request: AgentManualSmokeRequest): AgentManualSmokeSession = session
                    },
            )

        assertFailsWith<AgentManualSmokeUnexpectedToolException> {
            service.answer("ExamplePlayer", "KR1", "패치 질문", AnalysisRateLimitKey("127.0.0.1"), manualQuestionId = "approved")
        }

        assertFalse(dispatcher.dispatchEntered)
    }

    private class ToolSelectingModel : AgentModelGateway {
        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn =
            AgentModelTurn(
                text = null,
                toolCalls = listOf(AgentModelToolCall("call-1", GET_RANKED_STATS, "{\"groupBy\":\"POSITION\"}")),
                continuation = TestContinuation,
            )

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn = error("must not continue")
    }

    private class RecordingSession : AgentManualSmokeSession {
        override fun modelAttempt(
            number: Int,
            continuation: Boolean,
        ) = Unit

        override fun modelTurn(
            number: Int,
            turn: AgentModelTurn,
        ) = Unit

        override fun modelFailure(
            number: Int,
            exception: RuntimeException,
        ) = Unit

        override fun beforeToolDispatch(
            invocation: Int,
            call: AgentModelToolCall,
        ): Unit = throw AgentManualSmokeUnexpectedToolException()

        override fun toolOutput(
            invocation: Int,
            call: AgentModelToolCall,
            dispatched: AgentToolDispatchResult,
            serializedOutput: String,
            deliveredOutput: String,
            delivered: Boolean,
        ) = Unit

        override fun toolFailure(
            invocation: Int,
            call: AgentModelToolCall,
            exception: RuntimeException,
        ) = Unit

        override fun completed(response: AgentQuestionResponse) = Unit

        override fun failed(
            exception: RuntimeException,
            terminationReason: AgentTerminationReason?,
        ) = Unit

        override fun summary(summary: AgentQuestionExecutionSummary) = Unit
    }

    private object TestContinuation : AgentModelContinuation

    private class NeverDispatcher : AgentToolExecutor {
        var dispatchEntered = false

        override fun newContext(
            gameName: String,
            tagLine: String,
        ): AgentToolExecutionContext = AgentToolExecutionContext(gameName, tagLine, mock(PlayerComparisonContextService::class.java))

        override fun dispatch(
            call: AgentModelToolCall,
            context: AgentToolExecutionContext,
        ): AgentToolDispatchResult {
            dispatchEntered = true
            error("manual guard must block before dispatch")
        }

        override fun serialize(result: AgentToolDispatchResult): String = error("manual guard must block before serialize")

        override fun callSignature(call: AgentModelToolCall): String = error("manual guard must block before call signature")
    }

    private object DirectRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: Duration,
            action: () -> T,
        ): T = action()
    }
}
