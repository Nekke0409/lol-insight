package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.global.riot.MicrometerRiotApiObservationRecorder
import io.github.nekke0409.lolinsight.global.riot.RiotApiOutboundPacingTimeoutException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchmarkPreflightRiotObservationTest {
    @Test
    fun `prints measured safe metrics when preflight fails and preserves the failure`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerRiotApiObservationRecorder(registry)
        val output = mutableListOf<String>()

        assertFailsWith<RiotApiOutboundPacingTimeoutException> {
            BenchmarkPreflightRiotObservation(registry) { line ->
                output += line
                println(line)
            }.run { stage ->
                stage("comparison_context")
                recorder.recordHttpAttempt("match_detail")
                recorder.recordHttpResponse("match_detail", 200)
                recorder.recordPacingLockWait(Duration.ofMillis(3))
                recorder.recordPacingWait(Duration.ofMillis(2))
                recorder.recordPacingAdmissionElapsed(Duration.ofMillis(5))
                recorder.recordAdmissionTimeout("deadline")
                throw RiotApiOutboundPacingTimeoutException()
            }
        }

        val summary = output.single()
        assertContains(summary, "stage=comparison_context")
        assertContains(summary, "errorCode=RIOT_ADMISSION_TIMEOUT")
        assertContains(summary, "httpAttemptsByEndpoint={match_detail=1}")
        assertContains(summary, "responsesByStatus={200=1}")
        assertContains(summary, "localTimeoutCount=1")
        assertContains(summary, "timeoutBranches={deadline=1}")
        assertContains(summary, "lockWaitTotalMillis=3.0")
        assertContains(summary, "intervalWaitTotalMillis=2.0")
        assertContains(summary, "admissionElapsedTotalMillis=5.0")
        assertEquals(1, output.size)
    }

    @Test
    fun `observation output failure does not replace the preflight failure`() {
        val original = IllegalStateException("original")

        val failure =
            assertFailsWith<IllegalStateException> {
                BenchmarkPreflightRiotObservation(SimpleMeterRegistry()) { error("output failed") }.run { throw original }
            }

        assertEquals(original, failure)
    }
}
