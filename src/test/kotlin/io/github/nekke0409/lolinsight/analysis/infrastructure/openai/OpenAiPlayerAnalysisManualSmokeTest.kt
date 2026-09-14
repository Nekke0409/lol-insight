package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_SMOKE_TEST", matches = "true")
class OpenAiPlayerAnalysisManualSmokeTest {
    @Test
    fun `generates a Korean analysis from a deterministic fixture`() {
        val generator =
            OpenAiPlayerAnalysisGenerator(
                properties =
                    OpenAiProperties(
                        apiKey = System.getenv("OPENAI_API_KEY").orEmpty(),
                        model = System.getenv("OPENAI_MODEL") ?: "gpt-5-mini",
                    ),
                promptFactory = PlayerAnalysisPromptFactory(JsonMapper.builder().build()),
            )

        val result = generator.generate(TestPlayerAnalysisInput.input())

        assertTrue(result.summary.isNotBlank())
        assertEquals(result.observations, result.observations.filter { it.evidence.isNotBlank() })
    }
}
