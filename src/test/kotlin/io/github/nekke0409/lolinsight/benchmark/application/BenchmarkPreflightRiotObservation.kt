package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldownException
import io.github.nekke0409.lolinsight.global.riot.RiotApiOutboundPacingInterruptedException
import io.github.nekke0409.lolinsight.global.riot.RiotApiOutboundPacingTimeoutException
import io.github.nekke0409.lolinsight.global.riot.RiotApiResponseException
import io.github.nekke0409.lolinsight.global.riot.RiotApiTransportException
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit

/** Captures safe, low-cardinality Riot metrics before the opt-in test JVM shuts down. */
internal class BenchmarkPreflightRiotObservation(
    private val registry: MeterRegistry,
    private val output: (String) -> Unit = ::println,
) {
    fun <T> run(block: ((String) -> Unit) -> T): T {
        var stage = "settings"
        var failure: Throwable? = null
        try {
            return block { stage = it }
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            // Observation must never replace the original preflight failure.
            runCatching {
                output("benchmark_preflight_riot_observation=${summary(stage, failure)}")
            }
        }
    }

    private fun summary(
        stage: String,
        failure: Throwable?,
    ): BenchmarkPreflightRiotSummary =
        BenchmarkPreflightRiotSummary(
            stage = stage,
            errorCode = safeErrorCode(failure),
            httpAttemptsByEndpoint = countersByTag("riot.api.http.attempts", "endpoint"),
            responsesByStatus = countersByTag("riot.api.http.responses", "status"),
            decodeFailuresByEndpoint = countersByTag("riot.api.http.decode_failures", "endpoint"),
            upstream429Count = count("riot.api.http.rate_limits"),
            localTimeoutCount = count("riot.api.pacing.admissions", "outcome", "timeout"),
            timeoutBranches = countersByTag("riot.api.pacing.timeouts", "branch"),
            cooldownBlockedCount = count("riot.api.pacing.admissions", "outcome", "cooldown_blocked"),
            admissionInterruptedCount = count("riot.api.pacing.admissions", "outcome", "interrupted"),
            lockWaitCount = timerCount("riot.api.pacing.lock_waits"),
            lockWaitTotalMillis = timerTotalMillis("riot.api.pacing.lock_waits"),
            intervalWaitCount = timerCount("riot.api.pacing.waits"),
            intervalWaitTotalMillis = timerTotalMillis("riot.api.pacing.waits"),
            admissionElapsedCount = timerCount("riot.api.pacing.admission_elapsed"),
            admissionElapsedTotalMillis = timerTotalMillis("riot.api.pacing.admission_elapsed"),
            inFlightCompletion = "not_verified",
        )

    private fun countersByTag(
        name: String,
        tag: String,
    ): Map<String, Long> =
        registry
            .find(name)
            .counters()
            .groupBy { it.id.getTag(tag) ?: "unknown" }
            .toSortedMap()
            .mapValues { (_, counters) -> counters.sumOf { it.count().toLong() } }

    private fun count(
        name: String,
        vararg tags: String,
    ): Long =
        registry
            .find(name)
            .tags(*tags)
            .counters()
            .sumOf { it.count().toLong() }

    private fun timerCount(name: String): Long = registry.find(name).timers().sumOf { it.count() }

    private fun timerTotalMillis(name: String): Double = registry.find(name).timers().sumOf { it.totalTime(TimeUnit.MILLISECONDS) }

    private fun safeErrorCode(failure: Throwable?): String =
        when (failure) {
            null -> "NONE"
            is RiotApiOutboundPacingTimeoutException -> "RIOT_ADMISSION_TIMEOUT"
            is RiotApiOutboundPacingInterruptedException -> "RIOT_ADMISSION_INTERRUPTED"
            is RiotApiCooldownException -> "RIOT_COOLDOWN"
            is RiotApiResponseException -> if (failure.statusCode.value() == 429) "RIOT_429" else "RIOT_HTTP_ERROR"
            is RiotApiTransportException -> "RIOT_TRANSPORT_ERROR"
            else -> "OTHER_FAILURE"
        }
}

internal data class BenchmarkPreflightRiotSummary(
    val stage: String,
    val errorCode: String,
    val httpAttemptsByEndpoint: Map<String, Long>,
    val responsesByStatus: Map<String, Long>,
    val decodeFailuresByEndpoint: Map<String, Long>,
    val upstream429Count: Long,
    val localTimeoutCount: Long,
    val timeoutBranches: Map<String, Long>,
    val cooldownBlockedCount: Long,
    val admissionInterruptedCount: Long,
    val lockWaitCount: Long,
    val lockWaitTotalMillis: Double,
    val intervalWaitCount: Long,
    val intervalWaitTotalMillis: Double,
    val admissionElapsedCount: Long,
    val admissionElapsedTotalMillis: Double,
    val inFlightCompletion: String,
)
