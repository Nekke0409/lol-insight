package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.models.responses.StructuredResponseCreateParams
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiStructuredOutputSchemaTest {
    @Test
    fun `OpenAI SDK generates and locally validates the Java structured output schema`() {
        val params =
            StructuredResponseCreateParams
                .builder<OpenAiPlayerAnalysisOutput>()
                .model("gpt-5-mini")
                .input("{}")
                .text(OpenAiPlayerAnalysisOutput::class.java)
                .build()

        assertEquals(OpenAiPlayerAnalysisOutput::class.java, params.responseType)
        val format =
            params.rawParams
                .text()
                .orElseThrow()
                .format()
                .orElseThrow()
        assertTrue(format.isJsonSchema())
    }
}
