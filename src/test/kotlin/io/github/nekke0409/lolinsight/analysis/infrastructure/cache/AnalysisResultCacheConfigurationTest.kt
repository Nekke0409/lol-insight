package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.redis.connection.RedisConnectionFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisResultCacheConfigurationTest {
    @Test
    fun `serializes only a provider independent analysis result as JSON`() {
        val configuration = AnalysisResultCacheConfiguration()
        val template =
            configuration.analysisResultRedisTemplate(
                mock(RedisConnectionFactory::class.java),
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
            )
        val result =
            PlayerAnalysisResult(
                summary = "요약",
                observations = listOf(AnalysisInsight("관찰", "설명", "근거")),
                strengths = emptyList(),
                focusAreas = emptyList(),
                caveats = listOf("한계"),
            )

        val serializer =
            configuration.analysisResultValueSerializer(
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
            )
        val serialized = serializer.serialize(result)

        assertTrue(serialized.decodeToString().startsWith("{"))
        assertEquals(result, serializer.deserialize(serialized))
    }
}
