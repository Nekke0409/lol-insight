package io.github.nekke0409.lolinsight.analysis.ratelimit

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import java.time.Clock
import java.time.Duration

@Validated
@ConfigurationProperties("analysis.rate-limit")
data class AnalysisRateLimitProperties(
    @field:Min(1)
    val capacity: Long = 3,
    val window: Duration = Duration.ofMinutes(1),
) {
    init {
        require(!window.isZero && !window.isNegative) { "analysis.rate-limit.window must be positive" }
    }
}

@JvmInline
value class AnalysisRateLimitKey(
    val value: String,
)

class AnalysisGenerationRateLimitExceededException(
    val retryAfterSeconds: Long,
) : RuntimeException("Analysis generation rate limit exceeded")

class AnalysisGenerationRateLimiter(
    private val properties: AnalysisRateLimitProperties,
    private val clock: Clock,
    private val buckets: Cache<String, Bucket> =
        Caffeine
            .newBuilder()
            .expireAfterAccess(properties.window)
            .scheduler(Scheduler.systemScheduler())
            .build(),
) {
    fun check(key: AnalysisRateLimitKey) {
        val probe = buckets.get(key.value) { newBucket() }.tryConsumeAndReturnRemaining(1)
        if (!probe.isConsumed) {
            throw AnalysisGenerationRateLimitExceededException(retryAfterSeconds(probe.nanosToWaitForRefill))
        }
    }

    private fun newBucket(): Bucket =
        Bucket
            .builder()
            .withCustomTimePrecision(ClockTimeMeter(clock))
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(properties.capacity)
                    .refillIntervally(properties.capacity, properties.window)
                    .build(),
            ).build()

    private fun retryAfterSeconds(nanosToWait: Long): Long = ((nanosToWait + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).coerceAtLeast(1)

    private class ClockTimeMeter(
        private val clock: Clock,
    ) : TimeMeter {
        override fun currentTimeNanos(): Long = Math.multiplyExact(clock.millis(), NANOS_PER_MILLISECOND)

        override fun isWallClockBased(): Boolean = true
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisRateLimitProperties::class)
class AnalysisRateLimitConfiguration {
    @Bean
    fun analysisGenerationRateLimiter(
        properties: AnalysisRateLimitProperties,
        clock: Clock,
    ): AnalysisGenerationRateLimiter = AnalysisGenerationRateLimiter(properties, clock)
}
