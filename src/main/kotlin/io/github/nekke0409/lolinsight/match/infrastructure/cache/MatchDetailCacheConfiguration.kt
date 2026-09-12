package io.github.nekke0409.lolinsight.match.infrastructure.cache

import io.github.nekke0409.lolinsight.match.domain.Match
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration

const val MATCH_DETAIL_CACHE = "match:detail"
val MATCH_DETAIL_CACHE_TTL: Duration = Duration.ofDays(7)

@Configuration(proxyBeanMethods = false)
@EnableCaching
class MatchDetailCacheConfiguration {
    @Bean
    fun cacheManager(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
    ): RedisCacheManager =
        RedisCacheManager
            .builder(connectionFactory)
            .withInitialCacheConfigurations(
                mapOf(MATCH_DETAIL_CACHE to matchDetailCacheConfiguration(objectMapper)),
            ).disableCreateOnMissingCache()
            .build()

    internal fun matchDetailCacheConfiguration(objectMapper: ObjectMapper): RedisCacheConfiguration =
        RedisCacheConfiguration
            .defaultCacheConfig()
            .entryTtl(MATCH_DETAIL_CACHE_TTL)
            .disableCachingNullValues()
            .computePrefixWith { cacheName -> "$cacheName:" }
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(matchDetailValueSerializer(objectMapper)),
            )

    internal fun matchDetailValueSerializer(objectMapper: ObjectMapper): JacksonJsonRedisSerializer<Match> =
        JacksonJsonRedisSerializer(objectMapper, Match::class.java)
}
