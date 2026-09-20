package io.github.nekke0409.lolinsight.agent.web

import io.github.nekke0409.lolinsight.agent.application.AgentFeatureDisabledException
import io.github.nekke0409.lolinsight.agent.application.AgentModelContinuation
import io.github.nekke0409.lolinsight.agent.application.AgentModelGateway
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolOutput
import io.github.nekke0409.lolinsight.agent.application.AgentModelTurn
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionService
import io.github.nekke0409.lolinsight.agent.application.AgentToolDispatchResult
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutionContext
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutionRunner
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutor
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration

class AgentQuestionControllerTest {
    @Test
    fun `rejects agent HTTP requests by default before model or quota access`() {
        val rateLimiter = mock(AnalysisGenerationRateLimiter::class.java)
        val model = DisabledModelGateway()
        val service =
            AgentQuestionService(
                properties = AgentQuestionProperties(enabled = false),
                rateLimiter = rateLimiter,
                modelGateway = model,
                toolDispatcher = DisabledToolExecutor,
                toolExecutionRunner = DisabledToolExecutionRunner,
            )
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(AgentQuestionController(service, AnalysisRateLimitKeyResolver()))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()

        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/agent-questions", "Hide on bush", "KR1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"최근 미드 통계를 알려줘\"}"),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("AI agent is not enabled."))

        verifyNoInteractions(rateLimiter)
        kotlin.test.assertEquals(0, model.calls)
    }

    private class DisabledModelGateway : AgentModelGateway {
        var calls = 0

        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            calls++
            throw AssertionError("The disabled feature must not invoke the model")
        }

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            calls++
            throw AssertionError("The disabled feature must not invoke the model")
        }
    }

    private object DisabledToolExecutor : AgentToolExecutor {
        override fun newContext(
            gameName: String,
            tagLine: String,
        ): AgentToolExecutionContext = throw AgentFeatureDisabledException()

        override fun dispatch(
            call: io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall,
            context: AgentToolExecutionContext,
        ): AgentToolDispatchResult = throw AgentFeatureDisabledException()

        override fun serialize(result: AgentToolDispatchResult): String = throw AgentFeatureDisabledException()

        override fun callSignature(call: io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall): String =
            throw AgentFeatureDisabledException()
    }

    private object DisabledToolExecutionRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: Duration,
            action: () -> T,
        ): T = throw AgentFeatureDisabledException()
    }
}
