package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.TestPlayerAnalysisInput
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class RedisPlayerAnalysisResultCacheIntegrationTest {
    private lateinit var connectionFactory: LettuceConnectionFactory

    @AfterEach
    fun destroyConnectionFactory() {
        if (::connectionFactory.isInitialized) {
            connectionFactory.destroy()
        }
    }

    @Test
    fun `returns a hit before Redis TTL expiry and a miss after expiry without sleeping`() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT)).apply { afterPropertiesSet() }
        val objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val template = AnalysisResultCacheConfiguration().analysisResultRedisTemplate(connectionFactory, objectMapper)
        val cache =
            RedisPlayerAnalysisResultCache(
                template,
                AnalysisResultCacheProperties(ttl = Duration.ofMinutes(30)),
                PlayerAnalysisInputFingerprint(objectMapper),
                SimpleMeterRegistry(),
            )
        val result = PlayerAnalysisResult("요약", emptyList(), emptyList(), emptyList(), emptyList())
        val key = requireNotNull(cache.keyFor(INPUT))

        cache.store(INPUT, result)

        assertEquals(result, cache.find(INPUT))

        template.expire(key, Duration.ZERO)

        assertNull(cache.find(INPUT))
    }

    private companion object {
        const val REDIS_PORT = 6379
        val INPUT = TestPlayerAnalysisInput.input()

        @Container
        @JvmStatic
        val redis = GenericContainer(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(REDIS_PORT)
    }
}
