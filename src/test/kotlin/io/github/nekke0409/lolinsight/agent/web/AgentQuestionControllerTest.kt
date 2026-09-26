package io.github.nekke0409.lolinsight.agent.web

import io.github.nekke0409.lolinsight.agent.application.AgentFeatureDisabledException
import io.github.nekke0409.lolinsight.agent.application.AgentModelContinuation
import io.github.nekke0409.lolinsight.agent.application.AgentModelGateway
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolOutput
import io.github.nekke0409.lolinsight.agent.application.AgentModelTurn
import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteProperties
import io.github.nekke0409.lolinsight.agent.application.AgentPatchNoteScope
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionService
import io.github.nekke0409.lolinsight.agent.application.AgentStatementBasis
import io.github.nekke0409.lolinsight.agent.application.AgentStructuredFinalAnswer
import io.github.nekke0409.lolinsight.agent.application.AgentStructuredStatement
import io.github.nekke0409.lolinsight.agent.application.AgentToolDispatchResult
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutionContext
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutionRunner
import io.github.nekke0409.lolinsight.agent.application.AgentToolExecutor
import io.github.nekke0409.lolinsight.agent.application.SEARCH_PATCH_NOTES
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKeyResolver
import io.github.nekke0409.lolinsight.global.web.GlobalExceptionHandler
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidence
import io.github.nekke0409.lolinsight.rag.infrastructure.RagProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration
import java.util.UUID

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

    @Test
    fun `returns a validated patch-note citation from the existing Agent HTTP endpoint`() {
        val rateLimiter = mock(AnalysisGenerationRateLimiter::class.java)
        val service =
            AgentQuestionService(
                AgentQuestionProperties(enabled = true, patchNotes = AgentPatchNoteProperties(enabled = true)),
                rateLimiter,
                CitationScriptedModel(),
                CitationToolExecutor,
                DirectToolExecutionRunner,
                ragProperties = RagProperties(enabled = true),
            )
        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(AgentQuestionController(service, AnalysisRateLimitKeyResolver()))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()

        mockMvc
            .perform(
                post("/api/v1/players/{gameName}/{tagLine}/agent-questions", "Example", "KR1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"question":"패치 질문","knowledgeScope":{"patchVersion":"25.10","locale":"ko-KR"}}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value("패치 근거 답변"))
            .andExpect(jsonPath("$.usedTools[0].name").value("search_patch_notes"))
            .andExpect(jsonPath("$.citations[0].evidenceId").value("PATCH_E1"))
            .andExpect(jsonPath("$.citations[0].sourceUrl").value("https://example.test/patch"))
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

    private object DirectToolExecutionRunner : AgentToolExecutionRunner {
        override fun <T> execute(
            timeout: Duration,
            action: () -> T,
        ): T = action()
    }

    private class CitationScriptedModel : AgentModelGateway {
        private var turn = 0

        override fun start(
            question: String,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            turn++
            return toolTurn()
        }

        override fun continueWithToolOutputs(
            continuation: AgentModelContinuation,
            outputs: List<AgentModelToolOutput>,
            allowToolCalls: Boolean,
            timeout: Duration,
        ): AgentModelTurn {
            turn++
            return AgentModelTurn(
                text = null,
                toolCalls = emptyList(),
                continuation = TestContinuation,
                finalAnswer =
                    AgentStructuredFinalAnswer(
                        listOf(
                            AgentStructuredStatement(
                                "패치 근거 답변",
                                AgentStatementBasis.PATCH_NOTE,
                                listOf("PATCH_E1"),
                                SEARCH_PATCH_NOTES,
                            ),
                        ),
                        emptyList(),
                    ),
            )
        }

        private fun toolTurn(): AgentModelTurn =
            AgentModelTurn(
                text = null,
                toolCalls =
                    listOf(
                        io.github.nekke0409.lolinsight.agent.application
                            .AgentModelToolCall("call-1", SEARCH_PATCH_NOTES, "{\"query\":\"룰루\"}"),
                    ),
                continuation = TestContinuation,
            )
    }

    private object CitationToolExecutor : AgentToolExecutor {
        override fun newContext(
            gameName: String,
            tagLine: String,
        ): AgentToolExecutionContext = error("knowledgeScope is required")

        override fun newContext(
            gameName: String,
            tagLine: String,
            knowledgeScope: AgentPatchNoteScope?,
        ): AgentToolExecutionContext =
            AgentToolExecutionContext(
                gameName,
                tagLine,
                mock(io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService::class.java),
                knowledgeScope,
            )

        override fun dispatch(
            call: io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall,
            context: AgentToolExecutionContext,
        ): AgentToolDispatchResult =
            AgentToolDispatchResult.succeeded(
                SEARCH_PATCH_NOTES,
                mapOf("status" to "AVAILABLE", "evidence" to listOf(mapOf("evidenceId" to "PATCH_E1"))),
                emptyList(),
                patchNoteEvidence =
                    listOf(
                        PatchNoteEvidence(
                            "PATCH_E1",
                            UUID.fromString("11111111-1111-1111-1111-111111111111"),
                            UUID.fromString("22222222-2222-2222-2222-222222222222"),
                            "fixture",
                            "https://example.test/patch",
                            "25.10",
                            "ko-KR",
                            "revision",
                            listOf("챔피언", "룰루"),
                            "fixture text",
                        ),
                    ),
            )

        override fun serialize(result: AgentToolDispatchResult): String =
            "{\"status\":\"AVAILABLE\",\"evidence\":[{\"evidenceId\":\"PATCH_E1\"}]}"

        override fun callSignature(call: io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall): String =
            call.name + '\u0000' + call.arguments
    }

    private object TestContinuation : AgentModelContinuation
}
