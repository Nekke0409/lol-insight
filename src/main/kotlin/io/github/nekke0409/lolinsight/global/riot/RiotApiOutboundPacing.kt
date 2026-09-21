package io.github.nekke0409.lolinsight.global.riot

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A conservative, single-JVM request-start pacer. It deliberately does not model Riot quotas or
 * accumulate idle capacity: every admitted HTTP start reserves exactly one future interval.
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
    private val lock = ReentrantLock()
    private var nextAllowedNanos = Long.MIN_VALUE

    override fun awaitAdmission() {
        if (!properties.outboundPacing.enabled) return

        val startedAt = timeSource.nanoTime()
        val maxWaitNanos = properties.outboundPacing.maxWait.toNanos()
        try {
            if (!lock.tryLock(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                observationRecorder.recordAdmissionTimeout()
                throw RiotApiOutboundPacingTimeoutException()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            observationRecorder.recordAdmissionInterrupted()
            throw RiotApiOutboundPacingInterruptedException(exception)
        }

        try {
            val waitedNanos = waitForTurn(startedAt, maxWaitNanos)
            if (waitedNanos > 0) observationRecorder.recordPacingWait(Duration.ofNanos(waitedNanos))
            checkCooldownAdmission()
            nextAllowedNanos = addSaturated(timeSource.nanoTime(), properties.outboundPacing.minInterval.toNanos())
        } finally {
            lock.unlock()
        }
    }

    private fun waitForTurn(
        startedAt: Long,
        maxWaitNanos: Long,
    ): Long {
        val now = timeSource.nanoTime()
        val requiredWait = (nextAllowedNanos - now).coerceAtLeast(0)
        val elapsed = (now - startedAt).coerceAtLeast(0)
        if (requiredWait > (maxWaitNanos - elapsed).coerceAtLeast(0)) {
            observationRecorder.recordAdmissionTimeout()
            throw RiotApiOutboundPacingTimeoutException()
        }
        if (requiredWait == 0L) return 0

        try {
            timeSource.await(Duration.ofNanos(requiredWait))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            observationRecorder.recordAdmissionInterrupted()
            throw RiotApiOutboundPacingInterruptedException(exception)
        }
        return requiredWait
    }

    private fun checkCooldownAdmission() {
        try {
            cooldown.checkAdmission()
        } catch (exception: RiotApiCooldownException) {
            observationRecorder.recordCooldownBlocked()
            throw exception
        }
    }

    private fun addSaturated(
        left: Long,
        right: Long,
    ): Long = if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
