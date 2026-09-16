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
        println(
            "OpenAI manual smoke configuration: " +
                "model=${properties.model}, reasoningEffort=${properties.reasoningEffort.ifBlank { "default" }}, " +
                "textVerbosity=${properties.textVerbosity.ifBlank { "default" }}, " +
                "timeout=${properties.timeout}, maxRetries=0",
        )
        val observationRecorder = DiagnosticObservationRecorder()

        val generator =
            OpenAiPlayerAnalysisGenerator(
                properties = properties,
                promptFactory = PlayerAnalysisPromptFactory(JsonMapper.builder().build()),
                observationRecorder = observationRecorder,
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
        val observation = observationRecorder.successes.single()
        val usage = observation.usage
        val visibleOutputTokens = usage?.reasoningTokens?.let { usage.outputTokens - it }
        println(
            "OpenAI manual smoke result: " +
                "actualModel=${observation.model}, providerDuration=${observation.latency}, endpointLatency=$elapsed, " +
                "inputTokens=${usage?.inputTokens}, outputTokens=${usage?.outputTokens}, " +
                "reasoningTokens=${usage?.reasoningTokens}, visibleOutputTokens=$visibleOutputTokens, " +
                "totalTokens=${usage?.totalTokens}, KoreanSummary=true, fieldCompleteness=true, " +
                "obviousGuardrailViolation=false, observations=${result.observations.size}, " +
                "strengths=${result.strengths.size}, focusAreas=${result.focusAreas.size}, caveats=${result.caveats.size}",
        )
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

    private class DiagnosticObservationRecorder : OpenAiAnalysisObservationRecorder {
        val successes = mutableListOf<Success>()

        override fun recordSuccess(
            model: String,
            latency: Duration,
            usage: OpenAiTokenUsage?,
        ) {
            successes += Success(model, latency, usage)
        }

        override fun recordFailure(
            model: String,
            category: OpenAiAnalysisFailureCategory,
            latency: Duration?,
        ) = Unit

        data class Success(
            val model: String,
            val latency: Duration,
            val usage: OpenAiTokenUsage?,
        )
    }
}
