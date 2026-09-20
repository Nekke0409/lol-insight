package io.github.nekke0409.lolinsight.agent.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

interface AgentQuestionObservationRecorder {
    fun record(summary: AgentQuestionExecutionSummary)
}

data class AgentQuestionExecutionSummary(
    val modelRequestAttempts: Int,
    val toolExecutionAttempts: Int,
    val usedTools: List<AgentUsedTool>,
    val terminationReason: AgentTerminationReason?,
    val incompleteReason: AgentModelIncompleteReason?,
    val duration: Duration,
    val usage: List<AgentModelUsage>,
)

@Component
class SafeLoggingAgentQuestionObservationRecorder : AgentQuestionObservationRecorder {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(summary: AgentQuestionExecutionSummary) {
        logger.info(
            "agent_execution modelRequests={} toolAttempts={} tools={} terminationReason={} incompleteReason={} durationMs={} usage={}",
            summary.modelRequestAttempts,
            summary.toolExecutionAttempts,
            summary.usedTools.map { "${it.name}:${if (it.success) "success" else "failure"}" },
            summary.terminationReason?.name ?: "EXCEPTION",
            summary.incompleteReason?.name ?: "none",
            summary.duration.toMillis(),
            summary.usage.map { "input=${it.inputTokens},output=${it.outputTokens},total=${it.totalTokens}" },
        )
    }
}

internal object NoOpAgentQuestionObservationRecorder : AgentQuestionObservationRecorder {
    override fun record(summary: AgentQuestionExecutionSummary) = Unit
}
