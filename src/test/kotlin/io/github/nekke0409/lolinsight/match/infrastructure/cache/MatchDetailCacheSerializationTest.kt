package io.github.nekke0409.lolinsight.match.infrastructure.cache

import io.github.nekke0409.lolinsight.benchmark.persistence.NoDataSourceBenchmarkPersistenceTestConfiguration
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchMapper
import io.github.nekke0409.lolinsight.match.infrastructure.riot.RiotMatchResponseDto
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "riot.api.key=test-api-key",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@Import(NoDataSourceBenchmarkPersistenceTestConfiguration::class)
class MatchDetailCacheSerializationTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var cacheConfiguration: MatchDetailCacheConfiguration

    @Test
    fun `round trips a Match with Instant Duration and nested Kotlin data classes as JSON`() {
        val match = RiotMatchMapper.toMatch(matchResponse())
        val serializer = cacheConfiguration.matchDetailValueSerializer(objectMapper)
        val redisCacheConfiguration = cacheConfiguration.matchDetailCacheConfiguration(objectMapper)

        val serialized = serializer.serialize(match)

        assertEquals("match:detail:", redisCacheConfiguration.getKeyPrefixFor(MATCH_DETAIL_CACHE))
        assertEquals(
            MATCH_DETAIL_CACHE_TTL,
            redisCacheConfiguration.ttlFunction.getTimeToLive(match.matchId, match),
        )
        assertTrue(serialized.decodeToString().startsWith("{"))
        assertEquals(match, serializer.deserialize(serialized))
    }

    @Test
    fun `reads legacy objective payload and round trips absent atakhan with the configured Redis serializer`() {
        val serializer = cacheConfiguration.matchDetailValueSerializer(objectMapper)
        val legacy = RiotMatchMapper.toMatch(matchResponse())
        val legacyPayload = serializer.serialize(legacy)
        assertEquals(
            1,
            serializer
                .deserialize(legacyPayload)
                ?.teams
                ?.first()
                ?.objectives
                ?.atakhan
                ?.killCount,
        )

        val withoutAtakhan =
            RiotMatchMapper.toMatch(
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build().readValue(
                    requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail-without-atakhan.json")).readText(),
                    RiotMatchResponseDto::class.java,
                ),
            )
        val restored = serializer.deserialize(serializer.serialize(withoutAtakhan))
        assertEquals(withoutAtakhan, restored)
        assertNull(
            restored
                ?.teams
                ?.first()
                ?.objectives
                ?.atakhan,
        )
        assertEquals(8, restored?.participants?.first()?.kills)
        assertEquals(
            8,
            restored
                ?.teams
                ?.first()
                ?.objectives
                ?.tower
                ?.killCount,
        )

        val omitted = objectMapper.readTree(serializer.serialize(withoutAtakhan))
        (omitted.path("teams").path(0).path("objectives") as ObjectNode).remove("atakhan")
        assertEquals(withoutAtakhan, serializer.deserialize(objectMapper.writeValueAsBytes(omitted)))
    }

    private fun matchResponse(): RiotMatchResponseDto =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()
            .readValue(
                requireNotNull(javaClass.classLoader.getResource("riot/match/match-detail.json")).readText(),
                RiotMatchResponseDto::class.java,
            )
}
