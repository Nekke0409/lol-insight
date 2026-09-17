package io.github.nekke0409.lolinsight.analysis.ratelimit

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals

class AnalysisRateLimitPropertiesBindingTest {
    @Test
    fun `uses the MVP default policy when no override is set`() {
        val properties = bindApplicationYamlAnalysisRateLimitProperties()

        assertEquals(3, properties.capacity)
        assertEquals(Duration.ofMinutes(1), properties.window)
    }

    @Test
    fun `binds capacity and window environment overrides`() {
        val properties =
            bindApplicationYamlAnalysisRateLimitProperties(
                "ANALYSIS_RATE_LIMIT_CAPACITY" to "5",
                "ANALYSIS_RATE_LIMIT_WINDOW" to "2m",
            )

        assertEquals(5, properties.capacity)
        assertEquals(Duration.ofMinutes(2), properties.window)
    }

    private fun bindApplicationYamlAnalysisRateLimitProperties(
        vararg environmentValues: Pair<String, String>,
    ): AnalysisRateLimitProperties {
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

        return bindAnalysisRateLimitProperties(environment)
    }
}
