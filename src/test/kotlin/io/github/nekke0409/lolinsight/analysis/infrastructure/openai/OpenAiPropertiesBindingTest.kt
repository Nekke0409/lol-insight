package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals

class OpenAiPropertiesBindingTest {
    @Test
    fun `uses the existing twenty second default when timeout is absent`() {
        val properties = bindOpenAiProperties(MockEnvironment())

        assertEquals(Duration.ofSeconds(20), properties.timeout)
    }

    @Test
    fun `binds an explicit OpenAI timeout through Spring Boot configuration binding`() {
        val environment = MockEnvironment().withProperty("openai.timeout", "45s")

        val properties = bindOpenAiProperties(environment)

        assertEquals(Duration.ofSeconds(45), properties.timeout)
    }
}
