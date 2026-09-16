package io.github.nekke0409.lolinsight.analysis.job.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertFailsWith

class AnalysisJobServiceTest {
    private val lifecycleService = mock(AnalysisJobLifecycleService::class.java)
    private val dispatcher = mock(AnalysisJobDispatcher::class.java)
    private val service = AnalysisJobService(lifecycleService, dispatcher)

    @Test
    fun `creates the committed job before dispatching its in-memory command`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED)

        service.create(GAME_NAME, TAG_LINE, 0, 20)

        inOrder(lifecycleService, dispatcher).apply {
            verify(lifecycleService).createPending()
            verify(dispatcher).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20))
        }
    }

    @Test
    fun `marks a rejected pending job as capacity exceeded and does not return it`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED)
        doThrow(RejectedExecutionException())
            .`when`(dispatcher)
            .dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20))

        assertFailsWith<AnalysisJobCapacityExceededException> {
            service.create(GAME_NAME, TAG_LINE, 0, 20)
        }

        verify(lifecycleService).markRejectedIfPending(CREATED.jobId)
    }

    private companion object {
        const val GAME_NAME = "Hide on bush"
        const val TAG_LINE = "KR1"
        val CREATED =
            AnalysisJobCreated(
                UUID.fromString("e8741722-84c8-4d4f-9c1b-09c7a63418cf"),
                AnalysisJobStatus.PENDING,
                Instant.parse("2026-09-16T10:00:00Z"),
            )
    }
}
