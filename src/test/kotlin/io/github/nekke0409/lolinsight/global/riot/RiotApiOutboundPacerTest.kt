package io.github.nekke0409.lolinsight.global.riot

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RiotApiOutboundPacerTest {
    @Test
    fun `disabled pacing does not wait`() {
        val time = FakePacingTimeSource()
        pacer(enabled = false, time = time).awaitAdmission()

        assertEquals(emptyList(), time.waits)
    }

    @Test
    fun `enabled pacing reserves one interval and does not accumulate idle burst credit`() {
        val time = FakePacingTimeSource()
        val pacer = pacer(enabled = true, time = time)

        pacer.awaitAdmission()
        pacer.awaitAdmission()
        time.now += Duration.ofHours(1).toNanos()
        pacer.awaitAdmission()
        pacer.awaitAdmission()

        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofSeconds(2)), time.waits)
    }

    @Test
    fun `first admission works when the monotonic clock starts positive`() {
        val time = FakePacingTimeSource(initialNow = 1L)

        pacer(enabled = true, time = time).awaitAdmission()

        assertEquals(emptyList(), time.waits)
    }

    @Test
    fun `first admission works at zero negative and long boundaries`() {
        listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE - 1).forEach { initial ->
            val time = FakePacingTimeSource(initialNow = initial)
            val pacer = pacer(enabled = true, time = time)

            pacer.awaitAdmission()
            pacer.awaitAdmission()

            assertEquals(listOf(Duration.ofSeconds(2)), time.waits)
        }
    }

    @Test
    fun `an overdue wakeup times out before admission and a later independent request still works`() {
        val time = FakePacingTimeSource()
        val registry = SimpleMeterRegistry()
        val pacer = pacer(enabled = true, time = time, recorder = MicrometerRiotApiObservationRecorder(registry))
        pacer.awaitAdmission()
        time.afterWait = { time.now += Duration.ofSeconds(10).toNanos() }

        assertFailsWith<RiotApiOutboundPacingTimeoutException> { pacer.awaitAdmission() }
        assertEquals(
            1.0,
            registry
                .get("riot.api.pacing.timeouts")
                .tag("branch", "deadline")
                .counter()
                .count(),
        )

        time.afterWait = {}
        pacer.awaitAdmission()
    }

    @Test
    fun `interrupt on the first fast path is not admitted`() {
        val time = FakePacingTimeSource()
        val pacer = pacer(enabled = true, time = time)

        try {
            Thread.currentThread().interrupt()
            assertFailsWith<RiotApiOutboundPacingInterruptedException> { pacer.awaitAdmission() }
        } finally {
            Thread.interrupted()
        }
        pacer.awaitAdmission()
    }

    @Test
    fun `a fair timed lock admits an older waiter before a newer one`() {
        val firstWaiting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = mutableListOf<String>()
        val time =
            object : RiotApiPacingTimeSource {
                override fun nanoTime(): Long = 0L

                override fun await(duration: Duration) {
                    synchronized(order) { order += Thread.currentThread().name }
                    if (Thread.currentThread().name == "first") {
                        firstWaiting.countDown()
                        check(releaseFirst.await(2, TimeUnit.SECONDS))
                    }
                }
            }
        val pacer = pacer(enabled = true, time = time)
        pacer.awaitAdmission()
        val lock = pacerLock(pacer)
        val errors = mutableListOf<Throwable>()

        fun worker(name: String) =
            Thread({
                try {
                    pacer.awaitAdmission()
                } catch (exception: Throwable) {
                    synchronized(errors) { errors += exception }
                }
            }, name)
        val first = worker("first")
        val old = worker("old")
        val newest = worker("new")

        try {
            first.start()
            assertTrue(firstWaiting.await(2, TimeUnit.SECONDS))
            old.start()
            assertTrue(awaitQueued(lock, old))
            newest.start()
            assertTrue(awaitQueued(lock, newest))
        } finally {
            releaseFirst.countDown()
        }
        listOf(first, old, newest).forEach { it.join(2_000) }

        assertTrue(errors.isEmpty(), "$errors")
        assertEquals(listOf("first", "old", "new"), order)
    }

    @Test
    fun `interrupt while waiting for lock does not reserve an admission`() {
        val pacer = pacer(enabled = true, time = FakePacingTimeSource())
        val lock = pacerLock(pacer)
        val failure = AtomicReference<Throwable>()
        val waiter =
            Thread({
                try {
                    pacer.awaitAdmission()
                } catch (exception: Throwable) {
                    failure.set(exception)
                }
            }, "lock-waiter")

        lock.lock()
        try {
            waiter.start()
            assertTrue(awaitQueued(lock, waiter))
            waiter.interrupt()
            waiter.join(2_000)
        } finally {
            lock.unlock()
        }

        assertTrue(failure.get() is RiotApiOutboundPacingInterruptedException)
        pacer.awaitAdmission()
    }

    @Test
    fun `a held lock causes a bounded lock wait timeout`() {
        val registry = SimpleMeterRegistry()
        val properties =
            RiotApiProperties(
                key = "test",
                outboundPacing =
                    RiotApiOutboundPacingProperties(
                        enabled = true,
                        minInterval = Duration.ofMillis(10),
                        maxWait = Duration.ofMillis(150),
                    ),
            )
        val pacer =
            RiotApiOutboundPacer(
                properties,
                RiotApiCooldown(properties, Clock.systemUTC()),
                SystemRiotApiPacingTimeSource(),
                MicrometerRiotApiObservationRecorder(registry),
            )
        val lock = pacerLock(pacer)
        val failure = AtomicReference<Throwable>()
        val waiter =
            Thread({
                try {
                    pacer.awaitAdmission()
                } catch (exception: Throwable) {
                    failure.set(exception)
                }
            }, "timed-waiter")

        lock.lock()
        try {
            waiter.start()
            assertTrue(awaitQueued(lock, waiter))
            waiter.join(2_000)
        } finally {
            lock.unlock()
        }

        assertTrue(failure.get() is RiotApiOutboundPacingTimeoutException)
        assertEquals(
            1.0,
            registry
                .get("riot.api.pacing.timeouts")
                .tag("branch", "lock_wait")
                .counter()
                .count(),
        )
        assertTrue(registry.get("riot.api.pacing.lock_waits").timer().totalTime(TimeUnit.MILLISECONDS) >= 100)
        pacer.awaitAdmission()
    }

    @Test
    fun `a lock acquired after the admission deadline is rejected before reservation`() {
        val time = FakePacingTimeSource()
        val registry = SimpleMeterRegistry()
        val pacer = pacer(enabled = true, time = time, recorder = MicrometerRiotApiObservationRecorder(registry))
        val lock = pacerLock(pacer)
        val failure = AtomicReference<Throwable>()
        val waiter =
            Thread({
                try {
                    pacer.awaitAdmission()
                } catch (exception: Throwable) {
                    failure.set(exception)
                }
            }, "late-lock-waiter")

        lock.lock()
        try {
            waiter.start()
            assertTrue(awaitQueued(lock, waiter))
            time.now += Duration.ofSeconds(11).toNanos()
        } finally {
            lock.unlock()
        }
        waiter.join(2_000)

        assertTrue(failure.get() is RiotApiOutboundPacingTimeoutException)
        assertEquals(
            1.0,
            registry
                .get("riot.api.pacing.timeouts")
                .tag("branch", "deadline")
                .counter()
                .count(),
        )
        pacer.awaitAdmission()
    }

    @Test
    fun `times out before HTTP admission when required wait exceeds the total deadline`() {
        val time = FakePacingTimeSource()
        val pacer = pacer(enabled = true, time = time, maxWait = Duration.ofSeconds(1))
        pacer.awaitAdmission()

        assertFailsWith<RiotApiOutboundPacingTimeoutException> { pacer.awaitAdmission() }
        assertEquals(emptyList(), time.waits)
    }

    @Test
    fun `checks cooldown again after pacing wait before allowing HTTP to start`() {
        val time = FakePacingTimeSource()
        val clock = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
        val cooldown = RiotApiCooldown(RiotApiProperties(key = "test"), clock)
        val pacer = pacer(enabled = true, time = time, cooldown = cooldown)
        pacer.awaitAdmission()
        time.afterWait = { cooldown.registerRateLimit(5) }

        assertFailsWith<RiotApiCooldownException> { pacer.awaitAdmission() }
    }

    @Test
    fun `preserves interruption and does not admit a request`() {
        val time = FakePacingTimeSource(interruptWait = true)
        val pacer = pacer(enabled = true, time = time)
        pacer.awaitAdmission()

        assertFailsWith<RiotApiOutboundPacingInterruptedException> { pacer.awaitAdmission() }
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
    }

    @Test
    fun `rejects non positive pacing durations`() {
        assertFailsWith<IllegalArgumentException> {
            RiotApiOutboundPacingProperties(minInterval = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            RiotApiOutboundPacingProperties(maxWait = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            RiotApiOutboundPacingProperties(minInterval = Duration.ofHours(2))
        }
        assertFailsWith<IllegalArgumentException> {
            RiotApiOutboundPacingProperties(maxWait = Duration.ofDays(400))
        }
    }

    private fun pacerLock(pacer: RiotApiOutboundPacer): ReentrantLock =
        RiotApiOutboundPacer::class.java.getDeclaredField("lock").let { field ->
            field.isAccessible = true
            field.get(pacer) as ReentrantLock
        }

    private fun awaitQueued(
        lock: ReentrantLock,
        thread: Thread,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (lock.hasQueuedThread(thread)) return true
            Thread.sleep(1)
        }
        return false
    }

    private fun pacer(
        enabled: Boolean,
        time: RiotApiPacingTimeSource,
        maxWait: Duration = Duration.ofSeconds(10),
        cooldown: RiotApiCooldown = RiotApiCooldown(RiotApiProperties(key = "test"), Clock.systemUTC()),
        recorder: RiotApiObservationRecorder = NoOpRiotApiObservationRecorder,
    ): RiotApiOutboundPacer =
        RiotApiOutboundPacer(
            properties =
                RiotApiProperties(
                    key = "test",
                    outboundPacing = RiotApiOutboundPacingProperties(enabled, Duration.ofSeconds(2), maxWait),
                ),
            cooldown = cooldown,
            timeSource = time,
            observationRecorder = recorder,
        )

    private class FakePacingTimeSource(
        private val interruptWait: Boolean = false,
        initialNow: Long = 0L,
    ) : RiotApiPacingTimeSource {
        var now = initialNow
        var afterWait: () -> Unit = {}
        val waits = mutableListOf<Duration>()

        override fun nanoTime(): Long = now

        override fun await(duration: Duration) {
            if (interruptWait) throw InterruptedException("test interruption")
            waits += duration
            now += duration.toNanos()
            afterWait()
        }
    }
}
