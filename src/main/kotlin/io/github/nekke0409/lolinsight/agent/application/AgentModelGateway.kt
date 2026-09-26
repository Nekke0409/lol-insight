package io.github.nekke0409.lolinsight.agent.application

import java.time.Duration

/** Provider-independent, per-request conversation boundary for the bounded agent loop. */
interface AgentModelGateway {
    fun start(
        question: String,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn

    fun start(
        question: String,
        allowedToolNames: Set<String>,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn = start(question, allowToolCalls, timeout)

    fun continueWithToolOutputs(
        continuation: AgentModelContinuation,
        outputs: List<AgentModelToolOutput>,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn

    fun continueWithToolOutputs(
        continuation: AgentModelContinuation,
        outputs: List<AgentModelToolOutput>,
        allowedToolNames: Set<String>,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn = continueWithToolOutputs(continuation, outputs, allowToolCalls, timeout)
}

interface AgentModelContinuation

data class AgentModelTurn(
    val text: String?,
    val toolCalls: List<AgentModelToolCall>,
    val continuation: AgentModelContinuation,
    val usage: AgentModelUsage? = null,
    val finalAnswer: AgentStructuredFinalAnswer? = null,
)

/** Token usage as returned by the provider. A missing value means the provider did not return usage. */
data class AgentModelUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    init {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens >= 0) { "outputTokens must not be negative" }
        require(totalTokens >= 0) { "totalTokens must not be negative" }
    }
}

data class AgentModelToolCall(
    val callId: String,
    val name: String,
    val arguments: String,
)

data class AgentModelToolOutput(
    val callId: String,
    val output: String,
)

data class AgentStructuredFinalAnswer(
    val statements: List<AgentStructuredStatement>,
    val limitations: List<String>,
)

data class AgentStructuredStatement(
    val text: String,
    val basis: AgentStatementBasis,
    val evidenceIds: List<String>,
    val toolName: String?,
)

enum class AgentStatementBasis {
    PATCH_NOTE,
    TOOL,
    LIMITATION,
}
