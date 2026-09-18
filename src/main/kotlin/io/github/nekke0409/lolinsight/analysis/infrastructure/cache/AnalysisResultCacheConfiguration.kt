package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import tools.jackson.databind.ObjectMapper

const val ANALYSIS_RESULT_CACHE_KEY_PREFIX = "analysis:result"

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisResultCacheProperties::class)
class AnalysisResultCacheConfiguration {
    @Bean("analysisResultRedisTemplate")
    fun analysisResultRedisTemplate(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
    ): RedisTemplate<String, PlayerAnalysisResult> =
        RedisTemplate<String, PlayerAnalysisResult>().apply {
            setConnectionFactory(connectionFactory)
            val stringKeySerializer = StringRedisSerializer()
            this.keySerializer = stringKeySerializer
            this.hashKeySerializer = stringKeySerializer
            val resultValueSerializer = analysisResultValueSerializer(objectMapper)
            this.valueSerializer = resultValueSerializer
            this.hashValueSerializer = resultValueSerializer
            afterPropertiesSet()
        }

    internal fun analysisResultValueSerializer(objectMapper: ObjectMapper): JacksonJsonRedisSerializer<PlayerAnalysisResult> =
        JacksonJsonRedisSerializer(objectMapper, PlayerAnalysisResult::class.java)
}
