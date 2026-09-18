package io.github.nekke0409.lolinsight.benchmark.application

import io.github.nekke0409.lolinsight.benchmark.domain.BenchmarkQueryWindow
import io.github.nekke0409.lolinsight.global.riot.RiotApiCooldown
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded internal workflow. It deliberately never loops until a coverage target is reached.
 * Riot I/O is performed by the existing seed service and outside cursor persistence transactions.
 */
@Service
class BenchmarkReplenishmentTickService(
    private val properties: BenchmarkReplenishmentProperties,
    private val benchmarkReplenishmentPlanner: BenchmarkReplenishmentPlanner,
    private val benchmarkCohortCoverageQueryService: BenchmarkCohortCoverageQueryService,
    private val benchmarkQueryWindowFactory: BenchmarkQueryWindowFactory,
    private val benchmarkReplenishmentCursorService: BenchmarkReplenishmentCursorService,
    private val benchmarkSeedService: BenchmarkSeedService,
    private val riotApiCooldown: RiotApiCooldown,
    private val observationRecorder: BenchmarkReplenishmentObservationRecorder,
) {
    private val tickRunning = AtomicBoolean(false)

    fun runOneTick(): BenchmarkReplenishmentTickResult {
        if (!tickRunning.compareAndSet(false, true)) {
            return BenchmarkReplenishmentTickResult(
                outcome = BenchmarkReplenishmentTickOutcome.ALREADY_RUNNING,
                before = emptyList(),
                after = emptyList(),
                attempts = emptyList(),
            ).also { observationRecorder.tick(it.outcome) }
        }

        try {
            val window = benchmarkQueryWindowFactory.current()
            val cohorts = properties.supportedCohorts()
            val before = plansFor(cohorts, window)

            if (riotApiCooldown.isBlocked()) {
                return BenchmarkReplenishmentTickResult(
                    outcome = BenchmarkReplenishmentTickOutcome.COOLDOWN_SKIPPED,
                    before = before,
                    after = before,
                    attempts = emptyList(),
                ).also {
                    observationRecorder.tick(it.outcome)
                    observationRecorder.rateLimitStopped()
                }
            }

            val selected =
                benchmarkReplenishmentPlanner
                    .prioritize(before)
                    .take(properties.maxCohortsPerTick)
            if (selected.isEmpty()) {
                return BenchmarkReplenishmentTickResult(
                    outcome = BenchmarkReplenishmentTickOutcome.HEALTHY,
                    before = before,
                    after = before,
                    attempts = emptyList(),
                ).also { observationRecorder.tick(it.outcome) }
            }

            val attempts = mutableListOf<BenchmarkReplenishmentAttempt>()
            var rateLimitStopped = false
            selected.forEach { plan ->
                if (rateLimitStopped || riotApiCooldown.isBlocked()) {
                    rateLimitStopped = true
                    return@forEach
                }

                observationRecorder.cohort("selected")
                val cursor = benchmarkReplenishmentCursorService.getOrCreate(plan.cohort)
                val seedResult =
                    benchmarkSeedService.seed(
                        BenchmarkSeedRequest(
                            tier = plan.cohort.tier,
                            division = plan.cohort.division,
                            startPage = cursor.nextPage,
                            pageCount = properties.pageCountPerCohort,
                            playerLimit = properties.playerLimitPerCohort,
                            matchesPerPlayer = properties.matchesPerPlayer,
                        ),
                    )

                // Discovery 429/cooldown preserves this page while recording attempt metadata.
                // Once discovery completed, collection failures do not rediscover it indefinitely.
                val discoveryRateLimited = seedResult.collectionResult == null && seedResult.rateLimitStopped
                val nextPage =
                    if (discoveryRateLimited) {
                        cursor.nextPage
                    } else {
                        nextPage(cursor.nextPage, seedResult)
                    }
                benchmarkReplenishmentCursorService.advance(cursor, nextPage)

                val outcome =
                    when {
                        discoveryRateLimited -> BenchmarkReplenishmentAttemptOutcome.DISCOVERY_RATE_LIMITED
                        seedResult.rateLimitStopped -> BenchmarkReplenishmentAttemptOutcome.COLLECTION_RATE_LIMITED
                        seedResult.emptyPageEncountered -> BenchmarkReplenishmentAttemptOutcome.EMPTY_PAGE_WRAPPED
                        else -> BenchmarkReplenishmentAttemptOutcome.COMPLETED
                    }
                attempts +=
                    BenchmarkReplenishmentAttempt(
                        cohort = plan.cohort,
                        requestedPage = cursor.nextPage,
                        nextPage = nextPage,
                        outcome = outcome,
                        seedResult = seedResult,
                    )
                observationRecorder.seed(outcome.metricValue)
                if (seedResult.rateLimitStopped) {
                    rateLimitStopped = true
                    observationRecorder.rateLimitStopped()
                }
            }

            val after = plansFor(cohorts, window)
            val outcome =
                if (rateLimitStopped) {
                    BenchmarkReplenishmentTickOutcome.RATE_LIMIT_STOPPED
                } else {
                    BenchmarkReplenishmentTickOutcome.COMPLETED
                }
            return BenchmarkReplenishmentTickResult(
                outcome = outcome,
                before = before,
                after = after,
                attempts = attempts,
            ).also { observationRecorder.tick(it.outcome) }
        } finally {
            tickRunning.set(false)
        }
    }

    private fun plansFor(
        cohorts: List<BenchmarkReplenishmentCohort>,
        window: BenchmarkQueryWindow,
    ): List<BenchmarkReplenishmentPlan> =
        cohorts.map { cohort ->
            benchmarkReplenishmentPlanner.plan(
                cohort,
                window,
                benchmarkCohortCoverageQueryService.findCoverage(cohort.coverageScope(), window),
            )
        }

    private fun nextPage(
        currentPage: Int,
        seedResult: BenchmarkSeedResult,
    ): Int {
        if (seedResult.emptyPageEncountered) {
            return FIRST_PAGE
        }
        val increment = seedResult.requestedPageCount
        return if (currentPage > Int.MAX_VALUE - increment) FIRST_PAGE else currentPage + increment
    }

    private companion object {
        const val FIRST_PAGE = 1
    }
}

data class BenchmarkReplenishmentTickResult(
    val outcome: BenchmarkReplenishmentTickOutcome,
    val before: List<BenchmarkReplenishmentPlan>,
    val after: List<BenchmarkReplenishmentPlan>,
    val attempts: List<BenchmarkReplenishmentAttempt>,
)

enum class BenchmarkReplenishmentTickOutcome(
    val metricValue: String,
) {
    HEALTHY("healthy"),
    COMPLETED("completed"),
    COOLDOWN_SKIPPED("cooldown_skipped"),
    RATE_LIMIT_STOPPED("rate_limit_stopped"),
    ALREADY_RUNNING("already_running"),
}

data class BenchmarkReplenishmentAttempt(
    val cohort: BenchmarkReplenishmentCohort,
    val requestedPage: Int,
    val nextPage: Int,
    val outcome: BenchmarkReplenishmentAttemptOutcome,
    val seedResult: BenchmarkSeedResult,
)

enum class BenchmarkReplenishmentAttemptOutcome(
    val metricValue: String,
) {
    COMPLETED("completed"),
    EMPTY_PAGE_WRAPPED("empty_page_wrapped"),
    DISCOVERY_RATE_LIMITED("discovery_rate_limited"),
    COLLECTION_RATE_LIMITED("collection_rate_limited"),
}
