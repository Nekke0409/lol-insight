package io.github.nekke0409.lolinsight.agent.application

import java.time.Duration

/** Provider-independent, per-request conversation boundary for the bounded agent loop. */
interface AgentModelGateway {
    fun start(
        question: String,
        timeout: Duration,
    ): AgentModelTurn

    fun continueWithToolOutputs(
        continuation: AgentModelContinuation,
        outputs: List<AgentModelToolOutput>,
        timeout: Duration,
    ): AgentModelTurn
}

interface AgentModelContinuation

data class AgentModelTurn(
    val text: String?,
    val toolCalls: List<AgentModelToolCall>,
    val continuation: AgentModelContinuation,
)

data class AgentModelToolCall(
    val callId: String,
    val name: String,
    val arguments: String,
)

data class AgentModelToolOutput(
    val callId: String,
    val output: String,
)
