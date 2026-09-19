package io.github.nekke0409.lolinsight.benchmark.scheduling

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentAttempt
import io.github.nekke0409.lolinsight.benchmark.application.BenchmarkReplenishmentTickResult
import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkCohortCoverage
import org.slf4j.LoggerFactory
import java.time.Instant

class BenchmarkReplenishmentRunOnceDiagnosticLogger {
    private val objectMapper =
        JsonMapper
            .builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
    private val logger = LoggerFactory.getLogger(javaClass)

    fun log(result: BenchmarkReplenishmentTickResult) {
        logger.info(
            "benchmark_replenishment_run_once_summary={}",
            objectMapper.writeValueAsString(BenchmarkReplenishmentRunOnceDiagnosticSummary.from(result)),
        )
    }
}

data class BenchmarkReplenishmentRunOnceDiagnosticSummary(
    val tickOutcome: String,
    val queryWindow: BenchmarkReplenishmentDiagnosticQueryWindow?,
    val cohorts: List<BenchmarkReplenishmentCohortDiagnostic>,
    val attempts: List<BenchmarkReplenishmentAttemptDiagnostic>,
) {
    companion object {
        fun from(result: BenchmarkReplenishmentTickResult): BenchmarkReplenishmentRunOnceDiagnosticSummary {
            val afterByCohort = result.after.associateBy { it.cohort }
            return BenchmarkReplenishmentRunOnceDiagnosticSummary(
                tickOutcome = result.outcome.name,
                queryWindow =
                    result.queryWindow?.let { window ->
                        BenchmarkReplenishmentDiagnosticQueryWindow(
                            fromInclusive = window.fromInclusive,
                            toExclusive = window.toExclusive,
                        )
                    },
                cohorts =
                    result.before.map { before ->
                        BenchmarkReplenishmentCohortDiagnostic(
                            cohort = "${before.cohort.tier}:${before.cohort.division}",
                            before = before.positionCoverage.map(::BenchmarkReplenishmentPositionCoverageDiagnostic),
                            after =
                                afterByCohort[before.cohort]
                                    ?.positionCoverage
                                    ?.map(::BenchmarkReplenishmentPositionCoverageDiagnostic)
                                    .orEmpty(),
                        )
                    },
                attempts = result.attempts.map(::BenchmarkReplenishmentAttemptDiagnostic),
            )
        }
    }
}

data class BenchmarkReplenishmentDiagnosticQueryWindow(
    val fromInclusive: Instant,
    val toExclusive: Instant,
)

data class BenchmarkReplenishmentCohortDiagnostic(
    val cohort: String,
    val before: List<BenchmarkReplenishmentPositionCoverageDiagnostic>,
    val after: List<BenchmarkReplenishmentPositionCoverageDiagnostic>,
)

data class BenchmarkReplenishmentPositionCoverageDiagnostic(
    val position: String,
    val sampleCount: Long,
    val uniquePlayerCount: Long,
    val availability: String,
    val samplesNeeded: Long,
    val uniquePlayersNeeded: Long,
) {
    constructor(coverage: BenchmarkCohortCoverage) : this(
        position = coverage.cohort.position,
        sampleCount = coverage.sampleCount,
        uniquePlayerCount = coverage.uniquePlayerCount,
        availability = coverage.availability.name,
        samplesNeeded = coverage.samplesNeeded,
        uniquePlayersNeeded = coverage.uniquePlayersNeeded,
    )
}

data class BenchmarkReplenishmentAttemptDiagnostic(
    val cohort: String,
    val requestedPage: Int,
    val pagesProcessed: Int,
    val nextPage: Int,
    val discoveryOutcome: String,
    val collectionOutcome: String,
    val createdSamples: Int,
    val skippedDuplicates: Int,
    val skippedInvalidSamples: Int,
    val rateLimitStopped: Boolean,
    val retryAfterSeconds: Long?,
) {
    constructor(attempt: BenchmarkReplenishmentAttempt) : this(
        cohort = "${attempt.cohort.tier}:${attempt.cohort.division}",
        requestedPage = attempt.requestedPage,
        pagesProcessed = attempt.seedResult.pagesProcessed,
        nextPage = attempt.nextPage,
        discoveryOutcome = attempt.discoveryOutcome(),
        collectionOutcome = attempt.collectionOutcome(),
        createdSamples = attempt.seedResult.createdSamples,
        skippedDuplicates = attempt.seedResult.skippedDuplicates,
        skippedInvalidSamples = attempt.seedResult.skippedInvalidSamples,
        rateLimitStopped = attempt.seedResult.rateLimitStopped,
        retryAfterSeconds = attempt.seedResult.retryAfterSeconds,
    )
}

private fun BenchmarkReplenishmentAttempt.discoveryOutcome(): String =
    when {
        seedResult.collectionResult == null && seedResult.rateLimitStopped -> "RATE_LIMITED"
        seedResult.emptyPageEncountered -> "EMPTY_PAGE"
        else -> "COMPLETED"
    }

private fun BenchmarkReplenishmentAttempt.collectionOutcome(): String =
    when {
        seedResult.collectionResult == null -> "NOT_ATTEMPTED"
        seedResult.collectionResult.rateLimitStopped -> "RATE_LIMITED"
        else -> "COMPLETED"
    }
