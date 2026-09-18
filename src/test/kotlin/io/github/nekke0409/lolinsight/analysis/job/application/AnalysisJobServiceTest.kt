package io.github.nekke0409.lolinsight.analysis.job.application

import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisJobServiceTest {
    private val lifecycleService = mock(AnalysisJobLifecycleService::class.java)
    private val dispatcher = mock(AnalysisJobDispatcher::class.java)
    private val inFlightRegistry = AnalysisJobInFlightRegistry(lifecycleService)
    private val service = AnalysisJobService(lifecycleService, dispatcher, inFlightRegistry)

    @Test
    fun `creates the committed job before dispatching its in-memory command`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED)

        assertEquals(CREATED, service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))

        inOrder(lifecycleService, dispatcher).apply {
            verify(lifecycleService).createPending()
            verify(dispatcher).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
        }
    }

    @Test
    fun `creates an automation job without reusing the HTTP in-flight registry`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED)

        assertEquals(CREATED, service.createFromAutomation(GAME_NAME, TAG_LINE, 0, 20))

        verify(dispatcher).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, null))
    }

    @Test
    fun `marks a rejected pending job as capacity exceeded and does not return it`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED)
        doThrow(RejectedExecutionException())
            .`when`(dispatcher)
            .dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))

        assertFailsWith<AnalysisJobCapacityExceededException> {
            service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        }

        verify(lifecycleService).markRejectedIfPending(CREATED.jobId)
    }

    @Test
    fun `returns the current in-flight job status without another row or dispatch`() {
        val running = CREATED.copy(status = AnalysisJobStatus.RUNNING)
        `when`(lifecycleService.createPending()).thenReturn(CREATED)
        `when`(lifecycleService.findInFlight(CREATED.jobId)).thenReturn(running)

        val first = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        val duplicate = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)

        assertEquals(CREATED, first)
        assertEquals(running, duplicate)
        verify(lifecycleService, times(1)).createPending()
        verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
    }

    @Test
    fun `creates distinct jobs for different request identities`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED, SECOND_CREATED)

        val first = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        val second = service.create(GAME_NAME, TAG_LINE, 1, 20, DIFFERENT_REQUEST_KEY)

        assertEquals(CREATED, first)
        assertEquals(SECOND_CREATED, second)
        verify(lifecycleService, times(2)).createPending()
        verify(dispatcher).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
        verify(dispatcher).dispatch(AnalysisJobCommand(SECOND_CREATED.jobId, GAME_NAME, TAG_LINE, 1, 20, DIFFERENT_REQUEST_KEY))
    }

    @Test
    fun `creates a new job after a terminal job is observed`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED, SECOND_CREATED)

        service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        val afterTerminal = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)

        assertEquals(SECOND_CREATED, afterTerminal)
        verify(lifecycleService, times(2)).createPending()
        verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
        verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(SECOND_CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
    }

    @Test
    fun `does not use executor capacity for a duplicate while a different request is rejected`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED, SECOND_CREATED)
        `when`(lifecycleService.findInFlight(CREATED.jobId)).thenReturn(CREATED)
        doThrow(RejectedExecutionException())
            .`when`(dispatcher)
            .dispatch(AnalysisJobCommand(SECOND_CREATED.jobId, GAME_NAME, TAG_LINE, 1, 20, DIFFERENT_REQUEST_KEY))

        service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        val duplicate = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)

        assertEquals(CREATED, duplicate)
        assertFailsWith<AnalysisJobCapacityExceededException> {
            service.create(GAME_NAME, TAG_LINE, 1, 20, DIFFERENT_REQUEST_KEY)
        }

        verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
        verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(SECOND_CREATED.jobId, GAME_NAME, TAG_LINE, 1, 20, DIFFERENT_REQUEST_KEY))
        verify(lifecycleService).markRejectedIfPending(SECOND_CREATED.jobId)
    }

    @Test
    fun `does not share an in-flight job with a different client`() {
        `when`(lifecycleService.createPending()).thenReturn(CREATED, SECOND_CREATED)

        val first = service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY)
        val otherClient = service.create(GAME_NAME, TAG_LINE, 0, 20, DIFFERENT_CLIENT_KEY)

        assertEquals(CREATED, first)
        assertEquals(SECOND_CREATED, otherClient)
        verify(lifecycleService, times(2)).createPending()
    }

    @Test
    fun `atomically shares one job and dispatch across concurrent duplicate requests`() {
        val threadCount = 12
        `when`(lifecycleService.createPending()).thenReturn(CREATED)
        `when`(lifecycleService.findInFlight(CREATED.jobId)).thenReturn(CREATED)
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val jobIds =
                executor
                    .invokeAll(
                        List(threadCount) {
                            java.util.concurrent.Callable {
                                barrier.await()
                                service.create(GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY).jobId
                            }
                        },
                    ).map { it.get() }

            assertEquals(setOf(CREATED.jobId), jobIds.toSet())
            verify(lifecycleService, times(1)).createPending()
            verify(dispatcher, times(1)).dispatch(AnalysisJobCommand(CREATED.jobId, GAME_NAME, TAG_LINE, 0, 20, DEDUPE_KEY))
        } finally {
            executor.shutdownNow()
        }
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
        val SECOND_CREATED =
            AnalysisJobCreated(
                UUID.fromString("1c565b91-2ac1-4e7a-b2d1-7adc3c766938"),
                AnalysisJobStatus.PENDING,
                Instant.parse("2026-09-16T10:01:00Z"),
            )
        val DEDUPE_KEY =
            AnalysisJobDedupeKey.of(
                AnalysisRateLimitKey("analysis-generation:client-a"),
                GAME_NAME,
                TAG_LINE,
                0,
                20,
            )
        val DIFFERENT_REQUEST_KEY =
            AnalysisJobDedupeKey.of(
                AnalysisRateLimitKey("analysis-generation:client-a"),
                GAME_NAME,
                TAG_LINE,
                1,
                20,
            )
        val DIFFERENT_CLIENT_KEY =
            AnalysisJobDedupeKey.of(
                AnalysisRateLimitKey("analysis-generation:client-b"),
                GAME_NAME,
                TAG_LINE,
                0,
                20,
            )
    }
}
