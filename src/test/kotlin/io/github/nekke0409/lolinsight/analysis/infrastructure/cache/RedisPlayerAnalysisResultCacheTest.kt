package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.TestPlayerAnalysisInput
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.serializer.SerializationException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedisPlayerAnalysisResultCacheTest {
    private val redisTemplate: RedisTemplate<String, PlayerAnalysisResult> = mock()
    private val valueOperations: ValueOperations<String, PlayerAnalysisResult> = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = AnalysisResultCacheProperties(ttl = Duration.ofMinutes(30))
    private val cache =
        RedisPlayerAnalysisResultCache(
            redisTemplate,
            properties,
            PlayerAnalysisInputFingerprint(
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
            ),
            meterRegistry,
        )

    @Test
    fun `returns a hit without exposing input values in the Redis key`() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(anyString())).thenReturn(RESULT)

        assertEquals(RESULT, cache.find(INPUT))

        verify(valueOperations).get(cache.keyFor(INPUT))
        assertEquals(1.0, meterRegistry.counter(ANALYSIS_RESULT_CACHE_REQUESTS_METRIC, "outcome", "hit").count())
    }

    @Test
    fun `uses a versioned SHA-256 key and changes keys across versions`() {
        val legacyVersion =
            RedisPlayerAnalysisResultCache(
                redisTemplate,
                AnalysisResultCacheProperties(ttl = Duration.ofMinutes(30), version = "analysis-result-v1"),
                PlayerAnalysisInputFingerprint(
                    JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                ),
                meterRegistry,
            )

        val firstKey = requireNotNull(cache.keyFor(INPUT))
        val secondKey = requireNotNull(legacyVersion.keyFor(INPUT))

        assertEquals("$ANALYSIS_RESULT_CACHE_KEY_PREFIX:analysis-result-v3", firstKey.substringBeforeLast(':'))
        assertEquals(64, firstKey.substringAfterLast(':').length)
        assertEquals(false, firstKey.contains("Hide on bush"))
        assertEquals(false, firstKey.contains("KR1"))
        assertEquals(false, firstKey.contains("MIDDLE"))
        assertEquals(false, firstKey == secondKey)
    }

    @Test
    fun `treats Redis read and corrupted-value failures as misses`() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(anyString())).thenThrow(SerializationException("invalid cache value"))
        val key = requireNotNull(cache.keyFor(INPUT))

        assertNull(cache.find(INPUT))

        verify(redisTemplate).delete(key)
        assertEquals(1.0, meterRegistry.counter(ANALYSIS_RESULT_CACHE_REQUESTS_METRIC, "outcome", "miss").count())
    }

    @Test
    fun `stores successful results with the configured ttl and ignores write failure`() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val key = requireNotNull(cache.keyFor(INPUT))

        cache.store(INPUT, RESULT)

        verify(valueOperations).set(key, RESULT, properties.ttl)

        doThrow(IllegalStateException("redis unavailable"))
            .`when`(valueOperations)
            .set(key, RESULT, properties.ttl)

        cache.store(INPUT, RESULT)
    }

    private companion object {
        val INPUT = TestPlayerAnalysisInput.input()
        val RESULT = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
    }
}
