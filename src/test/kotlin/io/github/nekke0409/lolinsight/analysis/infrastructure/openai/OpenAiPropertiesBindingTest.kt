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
    fun `uses low reasoning effort by default and binds a supported override`() {
        assertEquals(ReasoningEffort.LOW, bindApplicationYamlOpenAiProperties().requestedReasoningEffort())
        assertEquals(
            ReasoningEffort.MEDIUM,
            bindApplicationYamlOpenAiProperties("OPENAI_REASONING_EFFORT" to "medium").requestedReasoningEffort(),
        )
    }

    @Test
    fun `uses low text verbosity by default and binds a supported override`() {
        assertEquals(ResponseTextConfig.Verbosity.LOW, bindApplicationYamlOpenAiProperties().requestedTextVerbosity())
        assertEquals(
            ResponseTextConfig.Verbosity.HIGH,
            bindApplicationYamlOpenAiProperties("OPENAI_TEXT_VERBOSITY" to "high").requestedTextVerbosity(),
        )
    }

    @Test
    fun `rejects unsupported generation settings`() {
        assertFailsWith<OpenAiConfigurationException> {
            bindApplicationYamlOpenAiProperties("OPENAI_REASONING_EFFORT" to "none").requestedReasoningEffort()
        }
        assertFailsWith<OpenAiConfigurationException> {
            bindApplicationYamlOpenAiProperties("OPENAI_TEXT_VERBOSITY" to "verbose").requestedTextVerbosity()
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
