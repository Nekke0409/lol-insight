package io.github.nekke0409.lolinsight.rag.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.models.embeddings.CreateEmbeddingResponse
import com.openai.models.embeddings.Embedding
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.services.blocking.EmbeddingService
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingConfigurationException
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingInvalidResponseException
import io.github.nekke0409.lolinsight.rag.infrastructure.RagEmbeddingProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiPatchNoteEmbeddingGatewayTest {
    @Test
    fun `uses the configured model dimensions and ordered provider vectors`() {
        val client = mock(OpenAIClient::class.java)
        val embeddingService = mock(EmbeddingService::class.java)
        val response = mock(CreateEmbeddingResponse::class.java)
        val usage = mock(CreateEmbeddingResponse.Usage::class.java)
        val first = embedding(index = 0, values = listOf(1.0f, 0.0f, 0.0f))
        val second = embedding(index = 1, values = listOf(0.0f, 1.0f, 0.0f))
        `when`(client.embeddings()).thenReturn(embeddingService)
        `when`(embeddingService.create(anyEmbeddingParams(), anyRequestOptions())).thenReturn(response)
        `when`(response.model()).thenReturn("text-embedding-3-small")
        `when`(response.data()).thenReturn(listOf(second, first))
        `when`(response.usage()).thenReturn(usage)
        `when`(usage.promptTokens()).thenReturn(7)

        val result = gateway(client).embed(listOf("first", "second"))

        assertEquals(listOf(1.0f, 0.0f, 0.0f), result.vectors[0].values)
        assertEquals(listOf(0.0f, 1.0f, 0.0f), result.vectors[1].values)
        assertEquals(7, result.inputTokens)
        verify(embeddingService).create(anyEmbeddingParams(), anyRequestOptions())
    }

    @Test
    fun `rejects invalid input before constructing an OpenAI request`() {
        val gateway = gateway()

        assertFailsWith<RagEmbeddingInvalidResponseException> { gateway.embed(emptyList()) }
        assertFailsWith<RagEmbeddingInvalidResponseException> { gateway.embed(listOf(" ")) }
        assertFailsWith<RagEmbeddingInvalidResponseException> { gateway.embed(listOf("123456789")) }
        assertFailsWith<RagEmbeddingInvalidResponseException> { gateway.embed(listOf("one", "two", "three")) }
    }

    @Test
    fun `rejects an unexpected response index model or vector dimension`() {
        val client = mock(OpenAIClient::class.java)
        val embeddingService = mock(EmbeddingService::class.java)
        val response = mock(CreateEmbeddingResponse::class.java)
        val outOfOrderEmbedding = embedding(index = 1, values = listOf(1.0f, 0.0f, 0.0f))
        `when`(client.embeddings()).thenReturn(embeddingService)
        `when`(embeddingService.create(anyEmbeddingParams(), anyRequestOptions())).thenReturn(response)
        `when`(response.model()).thenReturn("another-model")
        `when`(response.data()).thenReturn(listOf(outOfOrderEmbedding))

        assertFailsWith<RagEmbeddingInvalidResponseException> {
            gateway(client).embed(listOf("input"))
        }
    }

    @Test
    fun `requires an api key only when a valid embedding request reaches the provider boundary`() {
        assertFailsWith<RagEmbeddingConfigurationException> {
            gateway().embed(listOf("input"))
        }
    }

    private fun gateway(client: OpenAIClient? = null): OpenAiPatchNoteEmbeddingGateway =
        OpenAiPatchNoteEmbeddingGateway(
            properties =
                RagEmbeddingProperties(
                    dimensions = 3,
                    maxBatchSize = 2,
                    maxInputCharacters = 8,
                    maxTotalBatchCharacters = 12,
                ),
            apiKey = "",
            clientOverride = client,
        )

    private fun embedding(
        index: Long,
        values: List<Float>,
    ): Embedding =
        mock(Embedding::class.java).also { embedding ->
            `when`(embedding.index()).thenReturn(index)
            `when`(embedding.embedding()).thenReturn(values)
        }

    @Suppress("UNCHECKED_CAST")
    private fun anyEmbeddingParams(): EmbeddingCreateParams {
        any(EmbeddingCreateParams::class.java)
        return EmbeddingCreateParams
            .builder()
            .model("text-embedding-3-small")
            .input("input")
            .build()
    }

    private fun anyRequestOptions(): RequestOptions {
        any(RequestOptions::class.java)
        return RequestOptions.none()
    }
}
