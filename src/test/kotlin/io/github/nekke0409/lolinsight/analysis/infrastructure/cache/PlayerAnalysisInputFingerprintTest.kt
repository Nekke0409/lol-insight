package io.github.nekke0409.lolinsight.analysis.infrastructure.cache

import io.github.nekke0409.lolinsight.analysis.infrastructure.openai.TestPlayerAnalysisInput
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PlayerAnalysisInputFingerprintTest {
    private val fingerprint =
        PlayerAnalysisInputFingerprint(
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .build(),
        )

    @Test
    fun `produces the same fingerprint for independently built equivalent canonical inputs`() {
        val first = TestPlayerAnalysisInput.input()
        val second =
            first.copy(
                comparisons = first.comparisons.map { comparison -> comparison.copy(metrics = comparison.metrics.toList()) },
                analysisLimitations = first.analysisLimitations.toList(),
            )

        assertEquals(fingerprint.create(first), fingerprint.create(second))
    }

    @Test
    fun `changes the fingerprint when an effective metric changes`() {
        val original = TestPlayerAnalysisInput.input()
        val changed =
            original.copy(
                comparisons =
                    original.comparisons.mapIndexed { index, comparison ->
                        if (index == 0) {
                            comparison.copy(metrics = comparison.metrics.map { it.copy(playerValue = 7.3) })
                        } else {
                            comparison
                        }
                    },
            )

        assertNotEquals(fingerprint.create(original), fingerprint.create(changed))
    }

    @Test
    fun `changes the fingerprint when the LLM-visible benchmark freshness policy changes`() {
        val original = TestPlayerAnalysisInput.input()
        val changed = original.copy(benchmarkFreshness = original.benchmarkFreshness.copy(maxSampleAge = Duration.ofDays(7)))

        assertNotEquals(fingerprint.create(original), fingerprint.create(changed))
    }

    @Test
    fun `rejects noncanonical comparison ordering before it can create a fingerprint`() {
        val input = TestPlayerAnalysisInput.input()

        assertFailsWith<IllegalArgumentException> {
            input.copy(comparisons = input.comparisons.reversed())
        }
    }
}
