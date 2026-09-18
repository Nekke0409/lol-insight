package io.github.nekke0409.lolinsight.global.riot

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiotApiCooldownTest {
    private val startedAt = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun `blocks before the deadline rounds a partial second up and allows at the deadline`() {
        val clock = MutableClock(startedAt)
        val cooldown = cooldown(clock)
        cooldown.registerRateLimit(10)

        clock.current = startedAt.plusSeconds(9).plusNanos(1)
        assertEquals(1, assertFailsWith<RiotApiCooldownException> { cooldown.checkAdmission() }.retryAfterSeconds)

        clock.current = startedAt.plusSeconds(10)
        cooldown.checkAdmission()
    }

    @Test
    fun `uses the configured fallback for missing and negative Retry-After values`() {
        val clock = MutableClock(startedAt)
        val cooldown = cooldown(clock, fallbackSeconds = 60)

        cooldown.registerRateLimit(null)
        assertEquals(60, assertFailsWith<RiotApiCooldownException> { cooldown.checkAdmission() }.retryAfterSeconds)

        val negativeCooldown = cooldown(MutableClock(startedAt), fallbackSeconds = 60)
        negativeCooldown.registerRateLimit(-1)
        assertEquals(60, assertFailsWith<RiotApiCooldownException> { negativeCooldown.checkAdmission() }.retryAfterSeconds)
    }

    @Test
    fun `keeps the latest deadline when concurrent rate limits arrive`() {
        val clock = MutableClock(startedAt)
        val cooldown = cooldown(clock)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            listOf(5L, 12L).forEach { retryAfterSeconds ->
                executor.submit {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    cooldown.registerRateLimit(retryAfterSeconds)
                }
            }
            check(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            check(executor.awaitTermination(2, TimeUnit.SECONDS))

            assertEquals(12, assertFailsWith<RiotApiCooldownException> { cooldown.checkAdmission() }.retryAfterSeconds)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `does not shorten an existing cooldown and safely preserves an overflowing deadline`() {
        val clock = MutableClock(startedAt)
        val cooldown = cooldown(clock)
        cooldown.registerRateLimit(20)
        cooldown.registerRateLimit(0)
        assertEquals(20, assertFailsWith<RiotApiCooldownException> { cooldown.checkAdmission() }.retryAfterSeconds)

        val nearMaximumClock = MutableClock(Instant.MAX.minusSeconds(1))
        val overflowingCooldown = cooldown(nearMaximumClock)
        overflowingCooldown.registerRateLimit(Long.MAX_VALUE)
        assertEquals(1, assertFailsWith<RiotApiCooldownException> { overflowingCooldown.checkAdmission() }.retryAfterSeconds)
    }

    private fun cooldown(
        clock: Clock,
        fallbackSeconds: Long = 60,
    ): RiotApiCooldown =
        RiotApiCooldown(
            RiotApiProperties(key = "test-api-key", cooldownFallback = java.time.Duration.ofSeconds(fallbackSeconds)),
            clock,
        )

    private class MutableClock(
        var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }
}
