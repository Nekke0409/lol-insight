package io.github.nekke0409.lolinsight.global.riot

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Single-JVM request-start pacer. Each admission reserves a minimum interval from the previous
 * admission; the lock protects reservations, not the HTTP exchange itself.
 */
fun interface RiotApiOutboundPacing {
    fun awaitAdmission()
}

interface RiotApiPacingTimeSource {
    fun nanoTime(): Long

    @Throws(InterruptedException::class)
    fun await(duration: Duration)
}

@Component
class SystemRiotApiPacingTimeSource : RiotApiPacingTimeSource {
    override fun nanoTime(): Long = System.nanoTime()

    override fun await(duration: Duration) {
        TimeUnit.NANOSECONDS.sleep(duration.toNanos())
    }
}

@Component
class RiotApiOutboundPacer(
    private val properties: RiotApiProperties,
    private val cooldown: RiotApiCooldown,
    private val timeSource: RiotApiPacingTimeSource,
    private val observationRecorder: RiotApiObservationRecorder,
) : RiotApiOutboundPacing {
    // Timed tryLock honors fairness; untimed tryLock does not.
    private val lock = ReentrantLock(true)
    private var lastAdmissionNanos: Long? = null

    override fun awaitAdmission() {
        if (!properties.outboundPacing.enabled) return

        val startedAt = timeSource.nanoTime()
        val maxWaitNanos = properties.outboundPacing.maxWait.toNanos()
        try {
            acquireLock(startedAt, maxWaitNanos)
            try {
                checkDeadlineAndInterrupt(startedAt, maxWaitNanos)
                waitForTurn(startedAt, maxWaitNanos)
                checkDeadlineAndInterrupt(startedAt, maxWaitNanos)
                checkCooldownAdmission()
                checkDeadlineAndInterrupt(startedAt, maxWaitNanos)
                lastAdmissionNanos = timeSource.nanoTime()
            } finally {
                lock.unlock()
            }
        } finally {
            elapsedSince(startedAt)?.let { observationRecorder.recordPacingAdmissionElapsed(Duration.ofNanos(it)) }
        }
    }

    private fun acquireLock(
        startedAt: Long,
        maxWaitNanos: Long,
    ) {
        try {
            if (!lock.tryLock(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                timeout("lock_wait")
            }
        } catch (exception: InterruptedException) {
            interrupted(exception)
        } finally {
            elapsedSince(startedAt)?.let { observationRecorder.recordPacingLockWait(Duration.ofNanos(it)) }
        }
    }

    private fun waitForTurn(
        startedAt: Long,
        maxWaitNanos: Long,
    ) {
        val previousAdmission = lastAdmissionNanos ?: return
        val sincePrevious = elapsedSince(previousAdmission) ?: timeout("clock_range")
        val intervalNanos = properties.outboundPacing.minInterval.toNanos()
        val requiredWait = if (sincePrevious >= intervalNanos) 0L else intervalNanos - sincePrevious
        if (requiredWait == 0L) return

        val remaining = remainingNanos(startedAt, maxWaitNanos)
        if (requiredWait > remaining) timeout("interval_budget")

        val waitStartedAt = timeSource.nanoTime()

        try {
            timeSource.await(Duration.ofNanos(requiredWait))
        } catch (exception: InterruptedException) {
            interrupted(exception)
        } finally {
            elapsedSince(waitStartedAt)?.let { observationRecorder.recordPacingWait(Duration.ofNanos(it)) }
        }
    }

    private fun checkDeadlineAndInterrupt(
        startedAt: Long,
        maxWaitNanos: Long,
    ) {
        if (Thread.currentThread().isInterrupted) interrupted(InterruptedException("Riot pacing admission interrupted"))
        remainingNanos(startedAt, maxWaitNanos)
    }

    private fun remainingNanos(
        startedAt: Long,
        maxWaitNanos: Long,
    ): Long {
        val elapsed = elapsedSince(startedAt) ?: timeout("clock_range")
        if (elapsed >= maxWaitNanos) timeout("deadline")
        return maxWaitNanos - elapsed
    }

    private fun elapsedSince(startedAt: Long): Long? = (timeSource.nanoTime() - startedAt).takeIf { it >= 0 }

    private fun checkCooldownAdmission() {
        try {
            cooldown.checkAdmission()
        } catch (exception: RiotApiCooldownException) {
            observationRecorder.recordCooldownBlocked()
            throw exception
        }
    }

    private fun timeout(branch: String): Nothing {
        observationRecorder.recordAdmissionTimeout(branch)
        throw RiotApiOutboundPacingTimeoutException()
    }

    private fun interrupted(exception: InterruptedException): Nothing {
        Thread.currentThread().interrupt()
        observationRecorder.recordAdmissionInterrupted()
        throw RiotApiOutboundPacingInterruptedException(exception)
    }
}
