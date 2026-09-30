package io.github.nekke0409.lolinsight.agent.application

/**
 * Opt-in observation boundary for one approved local Agent smoke request.
 * Implementations must never be required for normal Agent traffic.
 */
interface AgentManualSmokeObserver {
    fun admit(request: AgentManualSmokeRequest): AgentManualSmokeSession
}

data class AgentManualSmokeRequest(
    val manualQuestionId: String?,
    val gameName: String,
    val tagLine: String,
    val question: String,
    val knowledgeScope: AgentPatchNoteScope?,
) {
    val path: String = "/api/v1/players/$gameName/$tagLine/agent-questions"
}

interface AgentManualSmokeSession {
    fun modelAttempt(
        number: Int,
        continuation: Boolean,
    )

    fun modelTurn(
        number: Int,
        turn: AgentModelTurn,
    )

    fun modelFailure(
        number: Int,
        exception: RuntimeException,
    )

    /** Called before callSignature/dispatcher so player-backed Tools cannot run in this smoke path. */
    fun beforeToolDispatch(
        invocation: Int,
        call: AgentModelToolCall,
    )

    /** The exact serialized value passed to the continuation, not a reconstructed payload. */
    fun toolOutput(
        invocation: Int,
        call: AgentModelToolCall,
        dispatched: AgentToolDispatchResult,
        serializedOutput: String,
        deliveredOutput: String,
        delivered: Boolean,
    )

    fun toolFailure(
        invocation: Int,
        call: AgentModelToolCall,
        exception: RuntimeException,
    )

    fun completed(response: AgentQuestionResponse)

    fun failed(
        exception: RuntimeException,
        terminationReason: AgentTerminationReason?,
    )

    fun summary(summary: AgentQuestionExecutionSummary)
}
