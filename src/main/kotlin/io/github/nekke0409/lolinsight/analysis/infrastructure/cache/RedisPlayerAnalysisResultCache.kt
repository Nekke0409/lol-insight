package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResultCache
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.stereotype.Component

internal const val ANALYSIS_RESULT_CACHE_REQUESTS_METRIC = "analysis.result.cache.requests"

@Component
class RedisPlayerAnalysisResultCache(
    @Qualifier("analysisResultRedisTemplate") private val redisTemplate: RedisTemplate<String, PlayerAnalysisResult>,
    private val properties: AnalysisResultCacheProperties,
    private val fingerprint: PlayerAnalysisInputFingerprint,
    private val meterRegistry: MeterRegistry,
) : PlayerAnalysisResultCache {
    override fun find(input: PlayerAnalysisInput): PlayerAnalysisResult? {
        val key = keyFor(input) ?: return miss()

        return try {
            redisTemplate.opsForValue().get(key).also { cached ->
                if (cached == null) {
                    miss()
                } else {
                    hit()
                }
            }
        } catch (_: SerializationException) {
            runCatching { redisTemplate.delete(key) }
            miss()
        } catch (_: Exception) {
            miss()
        }
    }

    override fun store(
        input: PlayerAnalysisInput,
        result: PlayerAnalysisResult,
    ) {
        val key = keyFor(input) ?: return

        runCatching {
            redisTemplate.opsForValue().set(key, result, properties.ttl)
        }
    }

    internal fun keyFor(input: PlayerAnalysisInput): String? =
        runCatching {
            "$ANALYSIS_RESULT_CACHE_KEY_PREFIX:${properties.version}:${fingerprint.create(input)}"
        }.getOrNull()

    private fun hit() {
        record("hit")
    }

    private fun miss(): PlayerAnalysisResult? {
        record("miss")
        return null
    }

    private fun record(outcome: String) {
        runCatching {
            Counter
                .builder(ANALYSIS_RESULT_CACHE_REQUESTS_METRIC)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment()
        }
    }
}
