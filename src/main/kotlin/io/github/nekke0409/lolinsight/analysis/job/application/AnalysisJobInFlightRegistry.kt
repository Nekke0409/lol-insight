package io.github.nekke0409.lolinsight.analysis.job.application

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Validated
@ConfigurationProperties("analysis.jobs.dedupe")
data class AnalysisJobDedupeProperties(
    @field:NotNull
    val expiry: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!expiry.isZero && !expiry.isNegative) { "analysis.jobs.dedupe.expiry must be positive" }
    }
}

/**
 * Coordinates only in-flight jobs in this JVM. It intentionally has no persistence or cross-instance behavior.
 */
class AnalysisJobInFlightRegistry(
    private val lifecycleService: AnalysisJobLifecycleService,
    private val entries: Cache<AnalysisJobDedupeKey, Entry> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .scheduler(Scheduler.systemScheduler())
            .build(),
) {
    fun acquire(
        key: AnalysisJobDedupeKey,
        createPending: () -> AnalysisJobCreated,
    ): Acquisition {
        var created: Entry? = null
        var existingJob: AnalysisJobCreated? = null

        fun setCreated(entry: Entry) {
            created = entry
        }

        val entry =
            requireNotNull(
                entries.asMap().compute(key) { _, existing ->
                    when {
                        existing == null -> createEntry(createPending, ::setCreated)
                        !existing.dispatchCompleted.isDone -> existing
                        else -> {
                            val inFlight = lifecycleService.findInFlight(existing.jobId)
                            if (inFlight != null) {
                                existingJob = inFlight
                                existing
                            } else {
                                createEntry(createPending, ::setCreated)
                            }
                        }
                    }
                },
            )

        return created?.let { Acquisition.Created(it) } ?: Acquisition.Existing(entry, existingJob)
    }

    fun markDispatched(entry: Entry) {
        entry.dispatchCompleted.complete(Unit)
    }

    fun reject(
        key: AnalysisJobDedupeKey,
        entry: Entry,
    ) {
        entries.asMap().remove(key, entry)
        entry.dispatchCompleted.completeExceptionally(AnalysisJobCapacityExceededException())
    }

    fun awaitDispatch(entry: Entry) {
        try {
            entry.dispatchCompleted.join()
        } catch (exception: CompletionException) {
            val cause = exception.cause
            if (cause is AnalysisJobCapacityExceededException) {
                throw cause
            }
            throw exception
        }
    }

    fun remove(
        key: AnalysisJobDedupeKey,
        jobId: UUID,
    ) {
        entries.asMap().computeIfPresent(key) { _, entry ->
            if (entry.jobId == jobId) {
                null
            } else {
                entry
            }
        }
    }

    private fun createEntry(
        createPending: () -> AnalysisJobCreated,
        created: (Entry) -> Unit,
    ): Entry = Entry(createPending()).also(created)

    sealed interface Acquisition {
        class Created internal constructor(
            val entry: Entry,
        ) : Acquisition

        class Existing internal constructor(
            val entry: Entry,
            val job: AnalysisJobCreated?,
        ) : Acquisition
    }

    class Entry internal constructor(
        val created: AnalysisJobCreated,
        val dispatchCompleted: CompletableFuture<Unit> = CompletableFuture(),
    ) {
        val jobId: UUID = created.jobId
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisJobDedupeProperties::class)
class AnalysisJobDedupeConfiguration {
    @Bean
    fun analysisJobInFlightRegistry(
        lifecycleService: AnalysisJobLifecycleService,
        properties: AnalysisJobDedupeProperties,
    ): AnalysisJobInFlightRegistry =
        AnalysisJobInFlightRegistry(
            lifecycleService,
            Caffeine
                .newBuilder()
                .expireAfterWrite(properties.expiry)
                .scheduler(Scheduler.systemScheduler())
                .build<AnalysisJobDedupeKey, AnalysisJobInFlightRegistry.Entry>(),
        )
}
