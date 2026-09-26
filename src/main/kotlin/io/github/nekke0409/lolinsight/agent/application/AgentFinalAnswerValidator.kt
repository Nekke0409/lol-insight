package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.rag.application.PatchNoteCitation

/**
 * Validates only provenance references. It deliberately does not claim to prove a model statement
 * is semantically true.
 */
internal object AgentFinalAnswerValidator {
    fun validateAndCompose(
        finalAnswer: AgentStructuredFinalAnswer,
        context: AgentToolExecutionContext,
        usedTools: List<AgentUsedTool>,
    ): AgentValidatedFinalAnswer {
        if (finalAnswer.statements.isEmpty() || finalAnswer.statements.size > MAX_STATEMENTS) {
            throw AgentModelInvalidResponseException()
        }
        if (finalAnswer.limitations.size > MAX_LIMITATIONS || finalAnswer.limitations.any(::isInvalidLimitation)) {
            throw AgentModelInvalidResponseException()
        }

        val successfulToolNames = usedTools.filter(AgentUsedTool::success).map(AgentUsedTool::name).toSet()
        val citations = linkedMapOf<String, PatchNoteCitation>()
        finalAnswer.statements.forEach { statement ->
            if (statement.text.isBlank() || statement.text.length > MAX_STATEMENT_CHARACTERS) {
                throw AgentModelInvalidResponseException()
            }
            if (
                statement.evidenceIds.size > MAX_EVIDENCE_IDS_PER_STATEMENT ||
                statement.evidenceIds.any { it.isBlank() || it.length > MAX_EVIDENCE_ID_CHARACTERS } ||
                statement.evidenceIds.distinct().size != statement.evidenceIds.size
            ) {
                throw AgentModelInvalidResponseException()
            }
            when (statement.basis) {
                AgentStatementBasis.PATCH_NOTE -> {
                    if (
                        statement.toolName != SEARCH_PATCH_NOTES ||
                        statement.evidenceIds.isEmpty() ||
                        SEARCH_PATCH_NOTES !in successfulToolNames
                    ) {
                        throw AgentModelInvalidResponseException()
                    }
                    val resolved = context.citationsFor(statement.evidenceIds) ?: throw AgentModelInvalidResponseException()
                    resolved.forEach { citations[it.evidenceId] = it }
                }

                AgentStatementBasis.TOOL -> {
                    if (
                        statement.toolName !in PLAYER_DATA_TOOL_NAMES ||
                        statement.toolName !in successfulToolNames ||
                        statement.evidenceIds.isNotEmpty()
                    ) {
                        throw AgentModelInvalidResponseException()
                    }
                }

                AgentStatementBasis.LIMITATION -> {
                    if (statement.toolName != null || statement.evidenceIds.isNotEmpty()) {
                        throw AgentModelInvalidResponseException()
                    }
                }
            }
        }

        return AgentValidatedFinalAnswer(
            answer = finalAnswer.statements.joinToString("\n") { it.text },
            citations = citations.values.toList(),
            limitations = finalAnswer.limitations,
        )
    }

    private fun isInvalidLimitation(value: String): Boolean = value.isBlank() || value.length > MAX_LIMITATION_CHARACTERS

    private const val MAX_STATEMENTS = 8
    private const val MAX_LIMITATIONS = 8
    private const val MAX_STATEMENT_CHARACTERS = 1_000
    private const val MAX_LIMITATION_CHARACTERS = 500
    private const val MAX_EVIDENCE_IDS_PER_STATEMENT = 8
    private const val MAX_EVIDENCE_ID_CHARACTERS = 64
    private val PLAYER_DATA_TOOL_NAMES = setOf(GET_RANKED_STATS, GET_PEER_COMPARISON)
}

internal data class AgentValidatedFinalAnswer(
    val answer: String,
    val citations: List<PatchNoteCitation>,
    val limitations: List<String>,
)
