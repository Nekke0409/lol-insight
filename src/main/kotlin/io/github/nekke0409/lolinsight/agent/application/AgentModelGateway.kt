package io.github.nekke0409.lolinsight.agent.application

import java.time.Duration

/** Provider-independent, per-request conversation boundary for the bounded agent loop. */
interface AgentModelGateway {
    fun start(
        question: String,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn

    fun continueWithToolOutputs(
        continuation: AgentModelContinuation,
        outputs: List<AgentModelToolOutput>,
        allowToolCalls: Boolean,
        timeout: Duration,
    ): AgentModelTurn
}

interface AgentModelContinuation

data class AgentModelTurn(
    val text: String?,
    val toolCalls: List<AgentModelToolCall>,
    val continuation: AgentModelContinuation,
    val usage: AgentModelUsage? = null,
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
