package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.env.StandardEnvironment
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiPlayerAnalysisManualSmokeTest {
    @Test
    fun `generates a Korean analysis from a deterministic fixture`() {
        val properties = bindOpenAiProperties(StandardEnvironment())
        println("OpenAI manual smoke configuration: timeout=${properties.timeout}, maxRetries=0")

        val generator =
            OpenAiPlayerAnalysisGenerator(
                properties = properties,
                promptFactory = PlayerAnalysisPromptFactory(JsonMapper.builder().build()),
            )

        val startedAt = System.nanoTime()
        val result = generator.generate(TestPlayerAnalysisInput.input())
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(result.summary.isNotBlank())
        assertEquals(result.observations, result.observations.filter { it.evidence.isNotBlank() })
        assertTrue(Regex("[가-힣]").containsMatchIn(result.summary))
        assertTrue((result.observations + result.strengths + result.focusAreas).all { it.hasNoBlankField() })
        assertTrue(result.caveats.all(String::isNotBlank))
        assertTrue(result.allText().hasNoObviousGuardrailViolation())
        println("OpenAI manual smoke result: elapsed=$elapsed, KoreanSummary=true, obviousGuardrailViolation=false")
    }

    private fun io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult.allText(): String =
        buildList {
            add(summary)
            observations.forEach { addAll(listOf(it.title, it.explanation, it.evidence)) }
            strengths.forEach { addAll(listOf(it.title, it.explanation, it.evidence)) }
            focusAreas.forEach { addAll(listOf(it.title, it.explanation, it.evidence)) }
            addAll(caveats)
        }.joinToString("\n")

    private fun io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight.hasNoBlankField(): Boolean =
        title.isNotBlank() && explanation.isNotBlank() && evidence.isNotBlank()

    private fun String.hasNoObviousGuardrailViolation(): Boolean =
        listOf(
            Regex("""(?i)\b(?:top|bottom)\s*(?:\d+|x)\s*%"""),
            Regex("""(?i)\b(?:player\s+)?percentile(?:\s+rank)?\b"""),
            Regex("""(?i)\bp90\b.{0,64}\b(?:player|players|percentile)\b"""),
            Regex("""상위\s*\d+\s*%|하위\s*\d+\s*%|플레이어\s*백분위"""),
        ).none { it.containsMatchIn(this) }
}
