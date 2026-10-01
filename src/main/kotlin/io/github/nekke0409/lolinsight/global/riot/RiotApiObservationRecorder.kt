package io.github.nekke0409.lolinsight.global.riot

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration

interface RiotApiObservationRecorder {
    fun recordHttpAttempt(endpoint: String)

    fun recordHttpResponse(
        endpoint: String,
        status: Int,
    )

    fun recordTransportFailure(endpoint: String)

    fun recordPacingWait(duration: Duration)

    fun recordPacingLockWait(duration: Duration)

    fun recordPacingAdmissionElapsed(duration: Duration)

    fun recordAdmissionTimeout(branch: String)

    fun recordAdmissionInterrupted()

    fun recordCooldownBlocked()

    fun recordUpstreamRateLimit(
        retryAfterSeconds: Long?,
        rateLimitType: String,
    )
}

object NoOpRiotApiObservationRecorder : RiotApiObservationRecorder {
    override fun recordHttpAttempt(endpoint: String) = Unit

    override fun recordHttpResponse(
        endpoint: String,
        status: Int,
    ) = Unit

    override fun recordTransportFailure(endpoint: String) = Unit

    override fun recordPacingWait(duration: Duration) = Unit

    override fun recordPacingLockWait(duration: Duration) = Unit

    override fun recordPacingAdmissionElapsed(duration: Duration) = Unit

    override fun recordAdmissionTimeout(branch: String) = Unit

    override fun recordAdmissionInterrupted() = Unit

    override fun recordCooldownBlocked() = Unit

    override fun recordUpstreamRateLimit(
        retryAfterSeconds: Long?,
        rateLimitType: String,
    ) = Unit
}

@Component
class MicrometerRiotApiObservationRecorder(
    private val meterRegistry: MeterRegistry,
) : RiotApiObservationRecorder {
    override fun recordHttpAttempt(endpoint: String) =
        safe { meterRegistry.counter("riot.api.http.attempts", "endpoint", endpoint).increment() }

    override fun recordHttpResponse(
        endpoint: String,
        status: Int,
    ) = safe {
        meterRegistry.counter("riot.api.http.responses", "endpoint", endpoint, "status", status.toString()).increment()
    }

    override fun recordTransportFailure(endpoint: String) =
        safe {
            meterRegistry.counter("riot.api.http.transport_failures", "endpoint", endpoint).increment()
        }

    override fun recordPacingWait(duration: Duration) {
        safe { meterRegistry.timer("riot.api.pacing.waits").record(duration) }
    }

    override fun recordPacingLockWait(duration: Duration) = safe { meterRegistry.timer("riot.api.pacing.lock_waits").record(duration) }

    override fun recordPacingAdmissionElapsed(duration: Duration) =
        safe { meterRegistry.timer("riot.api.pacing.admission_elapsed").record(duration) }

    override fun recordAdmissionTimeout(branch: String) =
        safe {
            meterRegistry.counter("riot.api.pacing.admissions", "outcome", "timeout").increment()
            meterRegistry.counter("riot.api.pacing.timeouts", "branch", branch).increment()
        }

    override fun recordAdmissionInterrupted() =
        safe {
            meterRegistry.counter("riot.api.pacing.admissions", "outcome", "interrupted").increment()
        }

    override fun recordCooldownBlocked() =
        safe {
            meterRegistry.counter("riot.api.pacing.admissions", "outcome", "cooldown_blocked").increment()
        }

    override fun recordUpstreamRateLimit(
        retryAfterSeconds: Long?,
        rateLimitType: String,
    ) = safe {
        meterRegistry
            .counter(
                "riot.api.http.rate_limits",
                "rate_limit_type",
                rateLimitType,
                "retry_after",
                if (retryAfterSeconds ==
                    null
                ) {
                    "absent"
                } else {
                    "present"
                },
            ).increment()
    }

    private fun safe(operation: () -> Unit) {
        runCatching(operation)
    }
}
