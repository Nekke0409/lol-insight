package io.github.nekke0409.lolinsight.analysis.job.application

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.nekke0409.lolinsight.analysis.ratelimit.AnalysisRateLimitKey
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnalysisJobInFlightRegistryTest {
    private val lifecycleService = mock(AnalysisJobLifecycleService::class.java)

    @Test
    fun `keeps client identity and exact request values isolated`() {
        val clientA = dedupeKey("client-a", 0)
        val sameClientAndRequest = dedupeKey("client-a", 0)
        val clientB = dedupeKey("client-b", 0)
        val differentStart = dedupeKey("client-a", 1)
        val differentRiotId = dedupeKey("client-a", 0, gameName = "Faker")
        val differentTagLine = dedupeKey("client-a", 0, tagLine = "NA1")
        val differentCount = dedupeKey("client-a", 0, count = 10)

        assertEquals(clientA, sameClientAndRequest)
        assertNotEquals(clientA, clientB)
        assertNotEquals(clientA, differentStart)
        assertNotEquals(clientA, differentRiotId)
        assertNotEquals(clientA, differentTagLine)
        assertNotEquals(clientA, differentCount)
        assertTrue(clientA.toString().contains(GAME_NAME).not())
    }

    @Test
    fun `removes a terminal job entry so the next request can create a job`() {
        val registry = AnalysisJobInFlightRegistry(lifecycleService)
        val key = dedupeKey("client-a", 0)
        val first = registry.acquire(key) { CREATED }
        val firstEntry = assertIs<AnalysisJobInFlightRegistry.Acquisition.Created>(first).entry
        registry.markDispatched(firstEntry)

        registry.remove(key, CREATED.jobId)

        val next = registry.acquire(key) { SECOND_CREATED }

        assertEquals(SECOND_CREATED.jobId, assertIs<AnalysisJobInFlightRegistry.Acquisition.Created>(next).entry.jobId)
    }

    @Test
    fun `expires a stale reservation without sleeping`() {
        val ticker = AtomicLong()
        val entries =
            Caffeine
                .newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .ticker { ticker.get() }
                .build<AnalysisJobDedupeKey, AnalysisJobInFlightRegistry.Entry>()
        val registry = AnalysisJobInFlightRegistry(lifecycleService, entries)
        val key = dedupeKey("client-a", 0)
        val first = assertIs<AnalysisJobInFlightRegistry.Acquisition.Created>(registry.acquire(key) { CREATED })
        registry.markDispatched(first.entry)

        ticker.addAndGet(Duration.ofMinutes(5).toNanos())
        entries.cleanUp()

        val afterExpiry = registry.acquire(key) { SECOND_CREATED }

        assertEquals(SECOND_CREATED.jobId, assertIs<AnalysisJobInFlightRegistry.Acquisition.Created>(afterExpiry).entry.jobId)
    }

    private fun dedupeKey(
        client: String,
        start: Int,
        gameName: String = GAME_NAME,
        tagLine: String = TAG_LINE,
        count: Int = 20,
    ): AnalysisJobDedupeKey =
        AnalysisJobDedupeKey.of(
            AnalysisRateLimitKey("analysis-generation:$client"),
            gameName,
            tagLine,
            start,
            count,
        )

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
    }
}
