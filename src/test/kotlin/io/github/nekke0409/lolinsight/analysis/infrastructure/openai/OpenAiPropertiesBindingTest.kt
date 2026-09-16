package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.models.ReasoningEffort
import com.openai.models.responses.ResponseTextConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiPropertiesBindingTest {
    @Test
    fun `uses the sixty second default and zero retries when timeout is absent`() {
        val properties = bindApplicationYamlOpenAiProperties()

        assertEquals(Duration.ofSeconds(60), properties.timeout)
        assertEquals(0, OPENAI_MAX_RETRIES)
    }

    @Test
    fun `binds an explicit OpenAI timeout and preserves zero retries`() {
        val properties = bindApplicationYamlOpenAiProperties("OPENAI_TIMEOUT" to "45s")

        assertEquals(Duration.ofSeconds(45), properties.timeout)
        assertEquals(0, OPENAI_MAX_RETRIES)
    }

    @Test
    fun `binds the diagnostic low reasoning override only when explicitly configured`() {
        assertEquals(null, bindApplicationYamlOpenAiProperties().requestedReasoningEffort())
        assertEquals(
            ReasoningEffort.LOW,
            bindApplicationYamlOpenAiProperties("OPENAI_REASONING_EFFORT" to "low").requestedReasoningEffort(),
        )
    }

    @Test
    fun `binds the diagnostic low text verbosity override only when explicitly configured`() {
        assertEquals(null, bindApplicationYamlOpenAiProperties().requestedTextVerbosity())
        assertEquals(
            ResponseTextConfig.Verbosity.LOW,
            bindApplicationYamlOpenAiProperties("OPENAI_TEXT_VERBOSITY" to "low").requestedTextVerbosity(),
        )
    }

    @Test
    fun `rejects an unsupported text verbosity value`() {
        assertFailsWith<OpenAiConfigurationException> {
            bindApplicationYamlOpenAiProperties("OPENAI_TEXT_VERBOSITY" to "medium").requestedTextVerbosity()
        }
    }

    private fun bindApplicationYamlOpenAiProperties(vararg environmentValues: Pair<String, String>): OpenAiProperties {
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

        return bindOpenAiProperties(environment)
    }
}
