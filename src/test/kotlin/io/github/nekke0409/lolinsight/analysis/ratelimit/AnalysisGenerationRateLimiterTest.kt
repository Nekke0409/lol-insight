package io.github.nekke0409.lolinsight.analysis.ratelimit

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bucket
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisGenerationRateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val limiter =
        AnalysisGenerationRateLimiter(
            AnalysisRateLimitProperties(capacity = 3, window = Duration.ofMinutes(1)),
            clock,
        )

    @Test
    fun `allows requests within a client quota`() {
        repeat(3) {
            limiter.check(CLIENT_A)
        }
    }

    @Test
    fun `rejects the request after the client quota is exhausted`() {
        repeat(3) {
            limiter.check(CLIENT_A)
        }

        val exception =
            assertFailsWith<AnalysisGenerationRateLimitExceededException> {
                limiter.check(CLIENT_A)
            }

        assertEquals(60, exception.retryAfterSeconds)
    }

    @Test
    fun `allows the client again after the configured window`() {
        repeat(3) {
            limiter.check(CLIENT_A)
        }
        clock.advance(Duration.ofMinutes(1))

        limiter.check(CLIENT_A)
    }

    @Test
    fun `does not share a quota between clients`() {
        repeat(3) {
            limiter.check(CLIENT_A)
        }

        assertFailsWith<AnalysisGenerationRateLimitExceededException> {
            limiter.check(CLIENT_A)
        }

        limiter.check(CLIENT_B)
    }

    @Test
    fun `expires an inactive client bucket`() {
        val ticker = AtomicLong()
        val buckets =
            Caffeine
                .newBuilder()
                .expireAfterAccess(Duration.ofMinutes(1))
                .ticker { ticker.get() }
                .build<String, Bucket>()
        val limiter = AnalysisGenerationRateLimiter(AnalysisRateLimitProperties(), clock, buckets)

        limiter.check(CLIENT_A)
        assertEquals(1, buckets.estimatedSize())

        ticker.addAndGet(Duration.ofMinutes(1).toNanos())
        buckets.cleanUp()

        assertEquals(0, buckets.estimatedSize())
    }

    private class MutableClock(
        private var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }

    private companion object {
        val CLIENT_A = AnalysisRateLimitKey("analysis-generation:client-a")
        val CLIENT_B = AnalysisRateLimitKey("analysis-generation:client-b")
    }
}
