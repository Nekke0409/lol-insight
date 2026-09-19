package io.github.nekke0409.lolinsight.automation.scheduling

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnalysisAutomationPropertiesBindingTest {
    @Test
    fun `uses the conservative disabled thirty minute polling default`() {
        val properties = bindApplicationYamlAnalysisAutomationProperties()

        assertFalse(properties.enabled)
        assertEquals(Duration.ofMinutes(30), properties.pollInterval)
        assertEquals(10, properties.batchSize)
    }

    @Test
    fun `binds explicit automation polling overrides`() {
        val properties =
            bindApplicationYamlAnalysisAutomationProperties(
                "ANALYSIS_AUTOMATION_ENABLED" to "true",
                "ANALYSIS_AUTOMATION_POLL_INTERVAL" to "7m",
                "ANALYSIS_AUTOMATION_BATCH_SIZE" to "3",
            )

        assertEquals(true, properties.enabled)
        assertEquals(Duration.ofMinutes(7), properties.pollInterval)
        assertEquals(3, properties.batchSize)
    }

    private fun bindApplicationYamlAnalysisAutomationProperties(
        vararg environmentValues: Pair<String, String>,
    ): AnalysisAutomationProperties {
        val environment = MockEnvironment()
        environment.propertySources.addLast(
            YamlPropertySourceLoader()
                .load("application.yaml", ClassPathResource("application.yaml"))
                .single(),
        )
        if (environmentValues.isNotEmpty()) {
            environment.propertySources.addFirst(
                SystemEnvironmentPropertySource("testEnvironment", environmentValues.toMap()),
            )
        }

        return bindAnalysisAutomationProperties(environment)
    }
}
