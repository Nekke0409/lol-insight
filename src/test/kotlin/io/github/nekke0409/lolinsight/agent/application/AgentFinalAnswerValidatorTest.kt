package io.github.nekke0409.lolinsight.agent.application

import io.github.nekke0409.lolinsight.comparison.application.PlayerComparisonContextService
import io.github.nekke0409.lolinsight.rag.application.PatchNoteEvidence
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentFinalAnswerValidatorTest {
    @Test
    fun `composes citations only from evidence delivered in this request`() {
        val context = context()
        context.acceptDeliveredPatchNoteEvidence(
            AgentToolDispatchResult.succeeded(
                SEARCH_PATCH_NOTES,
                mapOf("status" to "AVAILABLE"),
                emptyList(),
                patchNoteEvidence = listOf(evidence("PATCH_E1")),
            ),
        )

        val response =
            AgentFinalAnswerValidator.validateAndCompose(
                AgentStructuredFinalAnswer(
                    listOf(
                        AgentStructuredStatement(
                            "문서 근거 설명",
                            AgentStatementBasis.PATCH_NOTE,
                            listOf("PATCH_E1"),
                            SEARCH_PATCH_NOTES,
                        ),
                    ),
                    emptyList(),
                ),
                context,
                listOf(AgentUsedTool(SEARCH_PATCH_NOTES, true, 1)),
            )

        assertEquals(listOf("PATCH_E1"), response.citations.map { it.evidenceId })
        assertEquals("https://example.test/patch", response.citations.single().sourceUrl)
    }

    @Test
    fun `rejects a missing or non-delivered document evidence id instead of repairing it`() {
        val context = context()
        val answer =
            AgentStructuredFinalAnswer(
                listOf(
                    AgentStructuredStatement(
                        "근거 없는 문서 설명",
                        AgentStatementBasis.PATCH_NOTE,
                        listOf("PATCH_E9"),
                        SEARCH_PATCH_NOTES,
                    ),
                ),
                emptyList(),
            )

        assertFailsWith<AgentModelInvalidResponseException> {
            AgentFinalAnswerValidator.validateAndCompose(
                answer,
                context,
                listOf(AgentUsedTool(SEARCH_PATCH_NOTES, true, 1)),
            )
        }
    }

    @Test
    fun `allows a successful statistics statement without a patch-note citation`() {
        val response =
            AgentFinalAnswerValidator.validateAndCompose(
                AgentStructuredFinalAnswer(
                    listOf(
                        AgentStructuredStatement(
                            "최근 통계 설명",
                            AgentStatementBasis.TOOL,
                            emptyList(),
                            GET_RANKED_STATS,
                        ),
                    ),
                    emptyList(),
                ),
                context(),
                listOf(AgentUsedTool(GET_RANKED_STATS, true, 1)),
            )

        assertEquals(emptyList(), response.citations)
    }

    @Test
    fun `rejects a patch-note statement when its search Tool did not successfully execute`() {
        val context = context()
        context.acceptDeliveredPatchNoteEvidence(
            AgentToolDispatchResult.succeeded(
                SEARCH_PATCH_NOTES,
                mapOf("status" to "AVAILABLE"),
                emptyList(),
                patchNoteEvidence = listOf(evidence("PATCH_E1")),
            ),
        )

        assertFailsWith<AgentModelInvalidResponseException> {
            AgentFinalAnswerValidator.validateAndCompose(
                AgentStructuredFinalAnswer(
                    listOf(
                        AgentStructuredStatement(
                            "문서 근거 설명",
                            AgentStatementBasis.PATCH_NOTE,
                            listOf("PATCH_E1"),
                            SEARCH_PATCH_NOTES,
                        ),
                    ),
                    emptyList(),
                ),
                context,
                listOf(AgentUsedTool(SEARCH_PATCH_NOTES, false, 1)),
            )
        }
    }

    @Test
    fun `rejects a document search Tool statement that omits patch-note citations`() {
        assertFailsWith<AgentModelInvalidResponseException> {
            AgentFinalAnswerValidator.validateAndCompose(
                AgentStructuredFinalAnswer(
                    listOf(
                        AgentStructuredStatement(
                            "문서에 관한 사실 설명",
                            AgentStatementBasis.TOOL,
                            emptyList(),
                            SEARCH_PATCH_NOTES,
                        ),
                    ),
                    emptyList(),
                ),
                context(),
                listOf(AgentUsedTool(SEARCH_PATCH_NOTES, true, 1)),
            )
        }
    }

    private fun context(): AgentToolExecutionContext =
        AgentToolExecutionContext(
            "player",
            "KR1",
            mock(PlayerComparisonContextService::class.java),
            AgentPatchNoteScope("25.10", "ko-KR"),
        )

    private fun evidence(id: String): PatchNoteEvidence =
        PatchNoteEvidence(
            id,
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "fixture",
            "https://example.test/patch",
            "25.10",
            "ko-KR",
            "revision",
            listOf("챔피언", "룰루"),
            "fixture evidence",
        )
}
