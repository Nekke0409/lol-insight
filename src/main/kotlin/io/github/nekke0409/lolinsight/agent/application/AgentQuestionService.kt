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
    private val toolExecutionRunner: AgentToolExecutionRunner,
    private val observationRecorder: AgentQuestionObservationRecorder = NoOpAgentQuestionObservationRecorder,
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

        val executionStartedAt = System.nanoTime()
        val deadline = AgentExecutionDeadline.after(properties.executionDeadline)
        val toolContext = toolDispatcher.newContext(gameName, tagLine)
        val usedTools = mutableListOf<AgentUsedTool>()
        val limitations = linkedSetOf<String>()
        val handledCallSignatures = mutableSetOf<String>()
        val usage = mutableListOf<AgentModelUsage>()
        var modelRequests = 0
        var toolExecutions = 0
        var terminationReason: AgentTerminationReason? = null
        var incompleteReason: AgentModelIncompleteReason? = null
        var continuation: AgentModelContinuation? = null
        var outputsForContinuation: List<AgentModelToolOutput> = emptyList()

        fun finish(response: AgentQuestionResponse): AgentQuestionResponse {
            terminationReason = response.terminationReason
            return response
        }

        try {
            while (modelRequests < properties.maxModelRequests) {
                val modelTimeout =
                    deadline.nextTimeout(properties.modelRequestTimeout)
                        ?: return finish(
                            boundedResponse(usedTools, limitations, AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED),
                        )
                val allowToolCalls = modelRequests + 1 < properties.maxModelRequests && toolExecutions < properties.maxToolExecutions
                modelRequests++
                val turn =
                    if (continuation == null) {
                        modelGateway.start(question, allowToolCalls, modelTimeout)
                    } else {
                        modelGateway.continueWithToolOutputs(
                            continuation = checkNotNull(continuation),
                            outputs = outputsForContinuation,
                            allowToolCalls = allowToolCalls,
                            timeout = modelTimeout,
                        )
                    }
                turn.usage?.let(usage::add)
                if (deadline.isExpired()) {
                    return finish(
                        boundedResponse(usedTools, limitations, AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED),
                    )
                }

                if (turn.toolCalls.isEmpty()) {
                    val answer = turn.text?.trim().orEmpty()
                    return if (answer.isNotBlank()) {
                        finish(
                            AgentQuestionResponse(
                                answer = answer,
                                usedTools = usedTools,
                                dataLimitations = limitations.toList(),
                                terminationReason = AgentTerminationReason.COMPLETED,
                            ),
                        )
                    } else {
                        finish(
                            boundedResponse(usedTools, limitations, AgentTerminationReason.MODEL_RETURNED_NO_FINAL_ANSWER),
                        )
                    }
                }

                if (!allowToolCalls) {
                    limitations += "현재 모델 요청에서는 추가 Tool 실행이 허용되지 않습니다."
                    return finish(
                        boundedResponse(usedTools, limitations, AgentTerminationReason.TOOL_CALL_NOT_ALLOWED),
                    )
                }
                if (turn.toolCalls.size != 1) {
                    limitations += "한 번에 하나의 Tool 호출만 허용됩니다."
                    return finish(
                        boundedResponse(usedTools, limitations, AgentTerminationReason.PARALLEL_TOOL_CALL_REJECTED),
                    )
                }

                val call = turn.toolCalls.single()
                toolExecutions++
                val signature = toolDispatcher.callSignature(call)
                val dispatched =
                    if (!handledCallSignatures.add(signature)) {
                        AgentToolDispatchResult.failed(
                            toolName = observedAgentToolName(call.name),
                            code = "DUPLICATE_TOOL_CALL_BLOCKED",
                            message = "동일한 Tool과 인자의 반복 실행은 허용되지 않습니다.",
                        )
                    } else {
                        val toolTimeout =
                            deadline.nextTimeout(properties.executionDeadline)
                        if (toolTimeout == null) {
                            usedTools += AgentUsedTool(observedAgentToolName(call.name), success = false, invocation = toolExecutions)
                            return finish(
                                boundedResponse(
                                    usedTools,
                                    limitations,
                                    AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED,
                                ),
                            )
                        }
                        try {
                            toolExecutionRunner.execute(toolTimeout) { toolDispatcher.dispatch(call, toolContext) }
                        } catch (_: AgentToolExecutionDeadlineExceededException) {
                            usedTools += AgentUsedTool(observedAgentToolName(call.name), success = false, invocation = toolExecutions)
                            limitations += "Tool 조회가 Agent 전체 실행 시간을 초과하여 중단되었습니다."
                            return finish(
                                boundedResponse(
                                    usedTools,
                                    limitations,
                                    AgentTerminationReason.TOOL_EXECUTION_DEADLINE_EXCEEDED,
                                ),
                            )
                        } catch (_: AgentToolExecutionUnavailableException) {
                            usedTools += AgentUsedTool(observedAgentToolName(call.name), success = false, invocation = toolExecutions)
                            limitations += "현재 Agent Tool 실행을 시작할 수 없습니다."
                            return finish(
                                boundedResponse(
                                    usedTools,
                                    limitations,
                                    AgentTerminationReason.TOOL_EXECUTION_UNAVAILABLE,
                                ),
                            )
                        }
                    }
                if (deadline.isExpired()) {
                    usedTools += AgentUsedTool(dispatched.toolName, dispatched.success, toolExecutions)
                    limitations += dispatched.limitations
                    return finish(
                        boundedResponse(usedTools, limitations, AgentTerminationReason.EXECUTION_DEADLINE_EXCEEDED),
                    )
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
            return finish(boundedResponse(usedTools, limitations, AgentTerminationReason.MODEL_REQUEST_LIMIT_REACHED))
        } catch (exception: RuntimeException) {
            terminationReason =
                when (exception) {
                    is AgentModelIncompleteResponseException -> {
                        incompleteReason = exception.incompleteReason
                        AgentTerminationReason.MODEL_RESPONSE_INCOMPLETE
                    }

                    is AgentModelRefusalException -> AgentTerminationReason.MODEL_RESPONSE_REFUSED
                    is AgentModelAuthenticationException,
                    is AgentModelConfigurationException,
                    is AgentModelInvalidResponseException,
                    is AgentModelProviderException,
                    is AgentModelRateLimitException,
                    is AgentModelTransportException,
                    -> AgentTerminationReason.MODEL_REQUEST_FAILED

                    is AgentToolExecutionInterruptedException -> AgentTerminationReason.TOOL_EXECUTION_INTERRUPTED
                    else -> AgentTerminationReason.EXECUTION_FAILED
                }
            throw exception
        } finally {
            observationRecorder.recordSafely(
                AgentQuestionExecutionSummary(
                    modelRequestAttempts = modelRequests,
                    toolExecutionAttempts = toolExecutions,
                    usedTools = usedTools,
                    terminationReason = terminationReason,
                    incompleteReason = incompleteReason,
                    duration = Duration.ofNanos(System.nanoTime() - executionStartedAt),
                    usage = usage,
                ),
            )
        }
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

    private fun AgentQuestionObservationRecorder.recordSafely(summary: AgentQuestionExecutionSummary) {
        try {
            record(summary)
        } catch (_: Exception) {
            // Observability must not affect the Agent execution boundary.
        }
    }
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
    TOOL_CALL_NOT_ALLOWED,
    TOOL_EXECUTION_DEADLINE_EXCEEDED,
    TOOL_EXECUTION_UNAVAILABLE,
    TOOL_EXECUTION_INTERRUPTED,
    MODEL_REQUEST_LIMIT_REACHED,
    MODEL_RETURNED_NO_FINAL_ANSWER,
    MODEL_RESPONSE_INCOMPLETE,
    MODEL_RESPONSE_REFUSED,
    MODEL_REQUEST_FAILED,
    EXECUTION_FAILED,
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
