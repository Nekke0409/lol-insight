package io.github.nekke0409.lolinsight.global.riot

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
    }

    private fun pacer(
        enabled: Boolean,
        time: FakePacingTimeSource,
        maxWait: Duration = Duration.ofSeconds(10),
        cooldown: RiotApiCooldown = RiotApiCooldown(RiotApiProperties(key = "test"), Clock.systemUTC()),
    ): RiotApiOutboundPacer =
        RiotApiOutboundPacer(
            properties =
                RiotApiProperties(
                    key = "test",
                    outboundPacing = RiotApiOutboundPacingProperties(enabled, Duration.ofSeconds(2), maxWait),
                ),
            cooldown = cooldown,
            timeSource = time,
            observationRecorder = NoOpRiotApiObservationRecorder,
        )

    private class FakePacingTimeSource(
        private val interruptWait: Boolean = false,
    ) : RiotApiPacingTimeSource {
        var now = 0L
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
