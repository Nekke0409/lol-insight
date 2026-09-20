package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisGenerationRateLimiter
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class AgentQuestionService(
    private val properties: AgentQuestionProperties,
    private val rateLimiter: AnalysisGenerationRateLimiter,
    private val modelGateway: AgentModelGateway,
    private val toolDispatcher: AgentToolExecutor,
) {
    fun answer(
        gameName: String,
        tagLine: String,
        question: String,
        clientIdentity: AnalysisRateLimitKey,
    ): AgentQuestionResponse {
        properties.requireEnabled()
        requireQuestionWithinLimit(question)
        // This quota is intentionally consumed once per HTTP question, not once per loop turn.
        rateLimiter.check(clientIdentity)

        val deadline = AgentExecutionDeadline.after(properties.executionDeadline)
        val toolContext = toolDispatcher.newContext(gameName, tagLine)
        val usedTools = mutableListOf<AgentUsedTool>()
        val limitations = linkedSetOf<String>()
        val handledCallSignatures = mutableSetOf<String>()
        var modelRequests = 0
        var toolExecutions = 0
        var continuation: AgentModelContinuation? = null
        var outputsForContinuation: List<AgentModelToolOutput> = emptyList()

        while (modelRequests < properties.maxModelRequests) {
            val modelTimeout =
                deadline.nextTimeout(properties.modelRequestTimeout)
                    ?: return boundedResponse(usedTools, limitations, AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED)
            val turn =
                if (continuation == null) {
                    modelGateway.start(question, modelTimeout)
                } else {
                    modelGateway.continueWithToolOutputs(
                        continuation = checkNotNull(continuation),
                        outputs = outputsForContinuation,
                        timeout = modelTimeout,
                    )
                }
            modelRequests++
            if (deadline.isExpired()) {
                return boundedResponse(usedTools, limitations, AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED)
            }

            if (turn.toolCalls.isEmpty()) {
                val answer = turn.text?.trim().orEmpty()
                return if (answer.isNotBlank()) {
                    AgentQuestionResponse(
                        answer = answer,
                        usedTools = usedTools,
                        dataLimitations = limitations.toList(),
                        terminationReason = AgentTerminationReason.COMPLETED,
                    )
                } else {
                    boundedResponse(usedTools, limitations, AgentTerminationReason.MODEL_RETURNED_NO_FINAL_ANSWER)
                }
            }

            if (turn.toolCalls.size != 1) {
                limitations += "한 번에 하나의 Tool 호출만 허용됩니다."
                return boundedResponse(usedTools, limitations, AgentTerminationReason.PARALLEL_TOOL_CALL_REJECTED)
            }
            if (toolExecutions >= properties.maxToolExecutions) {
                limitations += "이 질문에서 허용된 Tool 실행 횟수에 도달했습니다."
                return boundedResponse(usedTools, limitations, AgentTerminationReason.TOOL_EXECUTION_LIMIT_REACHED)
            }

            val call = turn.toolCalls.single()
            toolExecutions++
            val signature = toolDispatcher.callSignature(call)
            val dispatched =
                if (!handledCallSignatures.add(signature)) {
                    AgentToolDispatchResult.failed(
                        toolName = call.name,
                        code = "DUPLICATE_TOOL_CALL_BLOCKED",
                        message = "동일한 Tool과 인자의 반복 실행은 허용되지 않습니다.",
                    )
                } else {
                    toolDispatcher.dispatch(call, toolContext)
                }
            val serializedOutput = toolDispatcher.serialize(dispatched)
            val boundedOutput = serializedOutput.withinToolResultLimit(properties.maxToolResultCharacters)
            usedTools += AgentUsedTool(dispatched.toolName, dispatched.success, toolExecutions)
            limitations += dispatched.limitations
            limitations += boundedOutput.limitations
            continuation = turn.continuation
            outputsForContinuation = listOf(AgentModelToolOutput(call.callId, boundedOutput.output))
        }

        limitations += "이 질문에서 허용된 모델 요청 횟수에 도달했습니다."
        return boundedResponse(usedTools, limitations, AgentTerminationReason.MODEL_REQUEST_LIMIT_REACHED)
    }

    private fun requireQuestionWithinLimit(question: String) {
        if (question.isBlank() || question.length > properties.maxQuestionCharacters) {
            throw AgentQuestionTooLongException()
        }
    }

    private fun boundedResponse(
        usedTools: List<AgentUsedTool>,
        limitations: Set<String>,
        reason: AgentTerminationReason,
    ): AgentQuestionResponse =
        AgentQuestionResponse(
            answer = "요청을 허용된 Agent 실행 범위 안에서 마무리하지 못했습니다. 데이터 범위와 질문을 확인해 주세요.",
            usedTools = usedTools,
            dataLimitations = limitations.toList(),
            terminationReason = reason,
        )
}

data class AgentQuestionResponse(
    val answer: String,
    val usedTools: List<AgentUsedTool>,
    val dataLimitations: List<String>,
    val terminationReason: AgentTerminationReason,
)

data class AgentUsedTool(
    val name: String,
    val success: Boolean,
    val invocation: Int,
)

enum class AgentTerminationReason {
    COMPLETED,
    EXECUTION_DEADLINE_EXCEEDED,
    PARALLEL_TOOL_CALL_REJECTED,
    TOOL_EXECUTION_LIMIT_REACHED,
    MODEL_REQUEST_LIMIT_REACHED,
    MODEL_RETURNED_NO_FINAL_ANSWER,
}

private class AgentExecutionDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun nextTimeout(maximum: Duration): Duration? {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) {
            return null
        }
        return minOf(maximum, Duration.ofNanos(remaining))
    }

    fun isExpired(): Boolean = System.nanoTime() >= deadlineNanos

    companion object {
        fun after(duration: Duration): AgentExecutionDeadline = AgentExecutionDeadline(Math.addExact(System.nanoTime(), duration.toNanos()))
    }
}

private data class BoundedToolOutput(
    val output: String,
    val limitations: List<String>,
)

private fun String.withinToolResultLimit(maximumCharacters: Int): BoundedToolOutput =
    if (length <= maximumCharacters) {
        BoundedToolOutput(this, emptyList())
    } else {
        BoundedToolOutput(
            output = """{"status":"RESULT_TOO_LARGE","message":"Tool result exceeded the configured response limit."}""",
            limitations = listOf("Tool 결과가 허용된 크기를 초과하여 상세 데이터가 제공되지 않았습니다."),
        )
    }
