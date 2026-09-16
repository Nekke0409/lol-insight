package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiMicrometerObservationRecorderTest {
    @Test
    fun `records successful request latency and SDK token usage`() {
        val registry = SimpleMeterRegistry()
        val recorder = OpenAiMicrometerObservationRecorder(registry)

        recorder.recordSuccess(
            model = MODEL,
            latency = Duration.ofMillis(250),
            usage = OpenAiTokenUsage(inputTokens = 100, outputTokens = 25, totalTokens = 125),
        )

        assertEquals(1.0, requestCount(registry, "success", "none"))
        assertEquals(1L, durationCount(registry, "success", "none"))
        assertEquals(100.0, tokenCount(registry, "input"))
        assertEquals(25.0, tokenCount(registry, "output"))
        assertEquals(125.0, tokenCount(registry, "total"))
    }

    @Test
    fun `records a usage-less response as success without token meters`() {
        val registry = SimpleMeterRegistry()
        val recorder = OpenAiMicrometerObservationRecorder(registry)

        recorder.recordSuccess(model = MODEL, latency = Duration.ofMillis(250), usage = null)

        assertEquals(1.0, requestCount(registry, "success", "none"))
        assertEquals(1L, durationCount(registry, "success", "none"))
        assertTrue(registry.find(AI_GENERATION_TOKENS_METRIC).meters().isEmpty())
    }

    @Test
    fun `records failure category and provider latency without token usage`() {
        val registry = SimpleMeterRegistry()
        val recorder = OpenAiMicrometerObservationRecorder(registry)

        recorder.recordFailure(
            model = MODEL,
            category = OpenAiAnalysisFailureCategory.RATE_LIMIT,
            latency = Duration.ofSeconds(1),
        )

        assertEquals(1.0, requestCount(registry, "failure", "rate_limit"))
        assertEquals(1L, durationCount(registry, "failure", "rate_limit"))
        assertTrue(registry.find(AI_GENERATION_TOKENS_METRIC).meters().isEmpty())
    }

    private fun requestCount(
        registry: SimpleMeterRegistry,
        outcome: String,
        errorCategory: String,
    ): Double =
        registry
            .get(AI_GENERATION_REQUESTS_METRIC)
            .tags(*requestTags(outcome, errorCategory))
            .counter()
            .count()

    private fun durationCount(
        registry: SimpleMeterRegistry,
        outcome: String,
        errorCategory: String,
    ): Long =
        registry
            .get(AI_GENERATION_DURATION_METRIC)
            .tags(*requestTags(outcome, errorCategory))
            .timer()
            .count()

    private fun tokenCount(
        registry: SimpleMeterRegistry,
        tokenType: String,
    ): Double =
        registry
            .get(AI_GENERATION_TOKENS_METRIC)
            .tags("provider", "openai", "model", MODEL, "token_type", tokenType)
            .counter()
            .count()

    private fun requestTags(
        outcome: String,
        errorCategory: String,
    ): Array<String> =
        arrayOf(
            "provider",
            "openai",
            "model",
            MODEL,
            "outcome",
            outcome,
            "error_category",
            errorCategory,
        )

    private companion object {
        const val MODEL = "gpt-5-mini"
    }
}
