package io.github.nekke0409.lolinsight.agent.infrastructure

import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeBudgetExceededException
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeExecutionPlanException
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeInputMismatchException
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeObserver
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeRequest
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeSession
import io.github.nekke0409.lolinsight.agent.application.AgentManualSmokeUnexpectedToolException
import io.github.nekke0409.lolinsight.agent.application.AgentModelToolCall
import io.github.nekke0409.lolinsight.agent.application.AgentModelTurn
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionExecutionSummary
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionProperties
import io.github.nekke0409.lolinsight.agent.application.AgentQuestionResponse
import io.github.nekke0409.lolinsight.agent.application.AgentTerminationReason
import io.github.nekke0409.lolinsight.agent.application.AgentToolDispatchResult
import io.github.nekke0409.lolinsight.agent.application.SEARCH_PATCH_NOTES
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

/**
 * A deliberately narrow local-only guard. It admits one pre-hashed request before any model call,
 * rejects player-backed Tools before dispatcher entry, and captures only bounded execution evidence.
 */
@Component
@ConditionalOnProperty(prefix = "agent.manual-smoke", name = ["enabled"], havingValue = "true")
class ManualAgentPatchNoteSmokeObserver(
    properties: AgentQuestionProperties,
    private val objectMapper: ObjectMapper,
) : AgentManualSmokeObserver {
    private val settings = ManualAgentSmokeSettings.from(properties)
    private val plan = ManualAgentSmokePlan.load(settings)
    private var admitted = false

    @Synchronized
    override fun admit(request: AgentManualSmokeRequest): AgentManualSmokeSession {
        if (admitted) throw AgentManualSmokeBudgetExceededException()
        if (request.manualQuestionId != plan.questionId ||
            request.gameName != plan.gameName ||
            request.tagLine != plan.tagLine ||
            request.path != plan.path ||
            request.knowledgeScope?.patchVersion != plan.patchVersion ||
            request.knowledgeScope.locale != plan.locale ||
            sha256(request.question.toByteArray(StandardCharsets.UTF_8)) != plan.questionSha256
        ) {
            throw AgentManualSmokeInputMismatchException()
        }
        val captureDirectory = settings.captureRoot.resolve(plan.runId)
        try {
            Files.createDirectories(settings.captureRoot)
            Files.createDirectory(captureDirectory)
        } catch (_: FileAlreadyExistsException) {
            throw AgentManualSmokeExecutionPlanException()
        }
        admitted = true
        val session = CaptureSession(captureDirectory, objectMapper)
        session.write(
            "request_admitted",
            mapOf(
                "runId" to plan.runId,
                "questionId" to plan.questionId,
                "questionSha256" to plan.questionSha256,
                "path" to plan.path,
                "patchVersion" to plan.patchVersion,
                "locale" to plan.locale,
                "limits" to plan.limits,
                "ragAnswerGeneratorInvoked" to false,
            ),
        )
        return session
    }
}

private class CaptureSession(
    directory: Path,
    private val objectMapper: ObjectMapper,
) : AgentManualSmokeSession {
    private val transcript = directory.resolve("server-execution.jsonl")
    private var modelAttempts = 0
    private var toolAttempts = 0
    private var patchSearchAttempts = 0

    override fun modelAttempt(number: Int, continuation: Boolean) {
        if (number > MAX_RESPONSES_REQUESTS) throw AgentManualSmokeBudgetExceededException()
        modelAttempts++
        write("responses_attempt_started", mapOf("number" to number, "continuation" to continuation))
    }

    override fun modelTurn(number: Int, turn: AgentModelTurn) {
        write(
            "responses_attempt_completed",
            mapOf(
                "number" to number,
                "usage" to turn.usage,
                "toolCalls" to turn.toolCalls.map { mapOf("name" to it.name, "arguments" to it.arguments) },
                "structuredFinal" to
                    turn.finalAnswer?.let { final ->
                        mapOf(
                            "statements" to final.statements.map { statement ->
                                mapOf(
                                    "text" to statement.text,
                                    "basis" to statement.basis.name,
                                    "evidenceIds" to statement.evidenceIds,
                                    "toolName" to statement.toolName,
                                )
                            },
                            "limitations" to final.limitations,
                        )
                    },
            ),
        )
    }

    override fun modelFailure(number: Int, exception: RuntimeException) {
        write("responses_attempt_failed", mapOf("number" to number, "failure" to exception.javaClass.simpleName))
    }

    override fun beforeToolDispatch(invocation: Int, call: AgentModelToolCall) {
        if (call.name != SEARCH_PATCH_NOTES) {
            write("tool_blocked_before_dispatch", mapOf("invocation" to invocation, "tool" to call.name))
            throw AgentManualSmokeUnexpectedToolException()
        }
        if (++toolAttempts > MAX_TOOL_EXECUTIONS || ++patchSearchAttempts > MAX_PATCH_NOTE_SEARCHES) {
            throw AgentManualSmokeBudgetExceededException()
        }
        write(
            "patch_note_tool_dispatch_started",
            mapOf(
                "invocation" to invocation,
                "tool" to call.name,
                "arguments" to call.arguments,
                "queryEmbeddingState" to "UNKNOWN_UNTIL_RETRIEVAL_RETURNS",
            ),
        )
    }

    override fun toolOutput(
        invocation: Int,
        call: AgentModelToolCall,
        dispatched: AgentToolDispatchResult,
        serializedOutput: String,
        deliveredOutput: String,
        delivered: Boolean,
    ) {
        val execution = dispatched.patchNoteSearchExecution
        write(
            "patch_note_tool_output_forwarded",
            mapOf(
                "invocation" to invocation,
                "tool" to call.name,
                "success" to dispatched.success,
                "serializedOutput" to serializedOutput,
                "forwardedOutput" to deliveredOutput,
                "delivered" to delivered,
                "queryEmbeddingAttempted" to execution?.queryEmbeddingAttempted,
                "queryEmbeddingInputTokens" to execution?.queryEmbeddingInputTokens,
                "retrievalResultCount" to execution?.resultCount,
                "evidence" to dispatched.patchNoteEvidence,
            ),
        )
    }

    override fun toolFailure(invocation: Int, call: AgentModelToolCall, exception: RuntimeException) {
        write(
            "patch_note_tool_failed",
            mapOf(
                "invocation" to invocation,
                "tool" to call.name,
                "failure" to exception.javaClass.simpleName,
                "queryEmbeddingState" to "UNOBSERVED_AFTER_TOOL_DISPATCH",
            ),
        )
    }

    override fun completed(response: AgentQuestionResponse) {
        write(
            "agent_response_completed",
            mapOf(
                "terminationReason" to response.terminationReason.name,
                "usedTools" to response.usedTools,
                "citationMapping" to response.citations,
                "dataLimitations" to response.dataLimitations,
            ),
        )
    }

    override fun failed(exception: RuntimeException, terminationReason: AgentTerminationReason?) {
        write(
            "agent_response_failed",
            mapOf("failure" to exception.javaClass.simpleName, "terminationReason" to terminationReason?.name),
        )
    }

    override fun summary(summary: AgentQuestionExecutionSummary) {
        write(
            "agent_execution_summary",
            mapOf(
                "reported" to summary,
                "observedResponsesAttempts" to modelAttempts,
                "observedToolAttempts" to toolAttempts,
                "observedPatchNoteSearchAttempts" to patchSearchAttempts,
                "ragAnswerGeneratorInvoked" to false,
            ),
        )
    }

    fun write(event: String, details: Any) {
        val line = objectMapper.writeValueAsString(mapOf("at" to Instant.now().toString(), "event" to event, "details" to details))
        Files.writeString(transcript, "$line\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private companion object {
        const val MAX_RESPONSES_REQUESTS = 3
        const val MAX_TOOL_EXECUTIONS = 2
        const val MAX_PATCH_NOTE_SEARCHES = 1
    }
}

private data class ManualAgentSmokeSettings(
    val planPath: Path,
    val approvedPlanSha256: String,
    val runId: String,
    val captureRoot: Path,
) {
    companion object {
        fun from(properties: AgentQuestionProperties): ManualAgentSmokeSettings {
            val manual = properties.manualSmoke
            if (!manual.enabled || manual.planPath.isBlank() || manual.planSha256.isBlank() || manual.evaluationRunId.isBlank()) {
                throw AgentManualSmokeExecutionPlanException()
            }
            if (!SHA_256.matches(manual.planSha256)) throw AgentManualSmokeExecutionPlanException()
            return ManualAgentSmokeSettings(Path.of(manual.planPath), manual.planSha256.lowercase(), manual.evaluationRunId, Path.of(manual.captureDirectory))
        }

        private val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}

private data class ManualAgentSmokePlan(
    val runId: String,
    val questionId: String,
    val questionSha256: String,
    val gameName: String,
    val tagLine: String,
    val path: String,
    val patchVersion: String,
    val locale: String,
    val limits: Map<String, Int>,
) {
    companion object {
        fun load(settings: ManualAgentSmokeSettings): ManualAgentSmokePlan {
            val bytes = try {
                Files.readAllBytes(settings.planPath)
            } catch (_: Exception) {
                throw AgentManualSmokeExecutionPlanException()
            }
            if (bytes.startsWithUtf8Bom() || sha256(bytes) != settings.approvedPlanSha256) throw AgentManualSmokeExecutionPlanException()
            val properties = Properties()
            try {
                properties.load(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
            } catch (_: Exception) {
                throw AgentManualSmokeExecutionPlanException()
            }
            val plan =
                ManualAgentSmokePlan(
                    runId = properties.required("runId"),
                    questionId = properties.required("questionId"),
                    questionSha256 = properties.requiredSha256("questionSha256"),
                    gameName = properties.required("gameName"),
                    tagLine = properties.required("tagLine"),
                    path = properties.required("path"),
                    patchVersion = properties.required("patchVersion"),
                    locale = properties.required("locale"),
                    limits =
                        mapOf(
                            "httpRequests" to properties.requiredPositive("maxHttpRequests"),
                            "responsesRequests" to properties.requiredPositive("maxResponsesRequests"),
                            "toolExecutions" to properties.requiredPositive("maxToolExecutions"),
                            "patchNoteSearches" to properties.requiredPositive("maxPatchNoteSearches"),
                            "queryEmbeddings" to properties.requiredPositive("maxQueryEmbeddings"),
                            "totalOpenAiAttempts" to properties.requiredPositive("maxOpenAiAttempts"),
                        ),
                )
            if (plan.runId != settings.runId || plan.limits != REQUIRED_LIMITS || plan.path != "/api/v1/players/${plan.gameName}/${plan.tagLine}/agent-questions") {
                throw AgentManualSmokeExecutionPlanException()
            }
            return plan
        }

        private val REQUIRED_LIMITS =
            mapOf(
                "httpRequests" to 1,
                "responsesRequests" to 3,
                "toolExecutions" to 2,
                "patchNoteSearches" to 1,
                "queryEmbeddings" to 1,
                "totalOpenAiAttempts" to 4,
            )
    }
}

private fun Properties.required(key: String): String = getProperty(key)?.takeIf(String::isNotBlank) ?: throw AgentManualSmokeExecutionPlanException()

private fun Properties.requiredSha256(key: String): String =
    required(key).lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: throw AgentManualSmokeExecutionPlanException()

private fun Properties.requiredPositive(key: String): Int = required(key).toIntOrNull()?.takeIf { it > 0 } ?: throw AgentManualSmokeExecutionPlanException()

private fun ByteArray.startsWithUtf8Bom(): Boolean = size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
