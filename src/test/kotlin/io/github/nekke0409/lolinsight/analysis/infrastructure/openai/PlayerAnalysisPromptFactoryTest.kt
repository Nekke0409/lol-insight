package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains

class PlayerAnalysisPromptFactoryTest {
    private val factory = PlayerAnalysisPromptFactory(JsonMapper.builder().build())

    @Test
    fun `includes multi-scope semantics and critical comparison prohibitions`() {
        val prompt = factory.create(TestPlayerAnalysisInput.input())

        assertContains(prompt.instructions, "POSITION is a role-level baseline")
        assertContains(prompt.instructions, "CHAMPION_POSITION is the exact champion-specific baseline")
        assertContains(prompt.instructions, "not a fallback relationship")
        assertContains(prompt.instructions, "Never combine numbers from different scopes")
        assertContains(prompt.instructions, "Do not make a champion-specific claim from a POSITION result")
        assertContains(prompt.instructions, "Do not generalize a CHAMPION_POSITION result")
        assertContains(prompt.instructions, "한국어")
        assertContains(prompt.instructions, "top X%")
        assertContains(prompt.instructions, "player percentile")
        assertContains(prompt.instructions, "match-level observation")
        assertContains(prompt.instructions, "good/bad")
        assertContains(prompt.instructions, "sample eligibility")
        assertContains(prompt.instructions, "timeline")
        assertContains(prompt.instructions, "gameVersions")
        assertContains(prompt.structuredData, "\"scope\":\"POSITION\"")
        assertContains(prompt.structuredData, "\"scope\":\"CHAMPION_POSITION\"")
        assertContains(prompt.structuredData, "\"championId\":null")
        assertContains(prompt.structuredData, "differenceFromMedian")
    }
}
