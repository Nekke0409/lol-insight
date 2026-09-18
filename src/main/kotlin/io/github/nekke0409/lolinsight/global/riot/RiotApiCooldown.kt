package io.github.nekke0409.lolinsight.global.riot

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * A conservative, JVM-local admission gate for Riot outbound requests after a 429 response.
 *
 * Riot documents `Retry-After` in seconds. A missing or malformed value therefore falls back to
 * the service policy instead of allowing a new request immediately. This is deliberately not a
 * proactive rate limiter or a distributed coordination mechanism.
 */
@Component
class RiotApiCooldown(
    private val properties: RiotApiProperties,
    private val clock: Clock,
) {
    private val blockedUntil = AtomicReference(Instant.EPOCH)

    fun checkAdmission() {
        val now = clock.instant()
        val until = blockedUntil.get()
        if (until.isAfter(now)) {
            throw RiotApiCooldownException(remainingSeconds(now, until))
        }
    }

    fun isBlocked(): Boolean = blockedUntil.get().isAfter(clock.instant())

    /**
     * Records an upstream 429 before its body is read. Multiple racing responses may only extend
     * the gate: a later registration never shortens an existing cooldown.
     */
    fun registerRateLimit(retryAfterSeconds: Long?) {
        val now = clock.instant()
        val duration = retryAfterSeconds?.takeIf { it >= 0 }?.let(Duration::ofSeconds) ?: properties.cooldownFallback
        val requestedUntil = addSafely(now, duration)
        blockedUntil.accumulateAndGet(requestedUntil, ::laterOf)
    }

    private fun addSafely(
        now: Instant,
        duration: Duration,
    ): Instant =
        try {
            now.plus(duration)
        } catch (_: DateTimeException) {
            Instant.MAX
        } catch (_: ArithmeticException) {
            Instant.MAX
        }

    private fun remainingSeconds(
        now: Instant,
        until: Instant,
    ): Long {
        val remaining = Duration.between(now, until)
        return if (remaining.nano == 0) {
            remaining.seconds
        } else {
            if (remaining.seconds == Long.MAX_VALUE) Long.MAX_VALUE else remaining.seconds + 1
        }
    }

    private fun laterOf(
        current: Instant,
        candidate: Instant,
    ): Instant = if (current.isAfter(candidate)) current else candidate
}
