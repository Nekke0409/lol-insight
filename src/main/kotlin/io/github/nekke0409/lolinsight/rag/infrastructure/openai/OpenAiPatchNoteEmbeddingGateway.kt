package io.github.nekke0409.lolinsight.rag.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.RequestOptions
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.embeddings.EmbeddingCreateParams
import io.github.nekke0409.lolinsight.rag.application.EmbeddingBatch
import io.github.nekke0409.lolinsight.rag.application.EmbeddingContract
import io.github.nekke0409.lolinsight.rag.application.EmbeddingGateway
import io.github.nekke0409.lolinsight.rag.application.EmbeddingVector
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingConfigurationException
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingInvalidResponseException
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingProviderException
import io.github.nekke0409.lolinsight.rag.application.RagEmbeddingTransportException
import io.github.nekke0409.lolinsight.rag.infrastructure.RagEmbeddingProperties
import java.time.Duration

/** OpenAI SDK details stay at the infrastructure edge; callers receive only provider-neutral vectors. */
internal class OpenAiPatchNoteEmbeddingGateway(
    private val properties: RagEmbeddingProperties,
    private val apiKey: String,
    private val clientOverride: OpenAIClient? = null,
) : EmbeddingGateway {
    override val contract: EmbeddingContract = EmbeddingContract(properties.model, properties.dimensions)

    private val client: OpenAIClient by lazy {
        clientOverride
            ?: run {
                if (apiKey.isBlank()) {
                    throw RagEmbeddingConfigurationException()
                }
                OpenAIOkHttpClient
                    .builder()
                    .apiKey(apiKey)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .build()
            }
    }

    override fun embed(inputs: List<String>): EmbeddingBatch = embed(inputs, properties.timeout)

    override fun embed(
        inputs: List<String>,
        timeout: Duration,
    ): EmbeddingBatch {
        validateInputs(inputs)
        require(!timeout.isNegative && !timeout.isZero) { "embedding timeout must be positive" }
        return try {
            val response =
                client
                    .embeddings()
                    .create(
                        EmbeddingCreateParams
                            .builder()
                            .model(contract.model)
                            .dimensions(contract.dimensions.toLong())
                            .inputOfArrayOfStrings(inputs)
                            .build(),
                        RequestOptions.builder().timeout(timeout).build(),
                    )
            if (response.model() != contract.model) {
                throw RagEmbeddingInvalidResponseException("embedding provider returned a different model")
            }
            val ordered = response.data().sortedBy { it.index() }
            if (ordered.map { it.index() } != inputs.indices.map(Int::toLong)) {
                throw RagEmbeddingInvalidResponseException("embedding provider response indices do not match input order")
            }
            val vectors = ordered.map { EmbeddingVector(it.embedding()) }
            if (vectors.any { it.values.size != contract.dimensions }) {
                throw RagEmbeddingInvalidResponseException("embedding provider returned an unexpected vector dimension")
            }
            EmbeddingBatch(contract, vectors, response.usage().promptTokens())
        } catch (exception: RagEmbeddingInvalidResponseException) {
            throw exception
        } catch (exception: OpenAIIoException) {
            throw RagEmbeddingTransportException(exception)
        } catch (exception: OpenAIInvalidDataException) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned malformed data", exception)
        } catch (exception: OpenAIServiceException) {
            throw RagEmbeddingProviderException(exception)
        } catch (exception: IllegalArgumentException) {
            throw RagEmbeddingInvalidResponseException("embedding provider returned malformed data", exception)
        }
    }

    private fun validateInputs(inputs: List<String>) {
        if (inputs.isEmpty()) {
            throw RagEmbeddingInvalidResponseException("embedding input must not be empty")
        }
        if (inputs.size > properties.maxBatchSize) {
            throw RagEmbeddingInvalidResponseException("embedding input batch exceeds the configured size")
        }
        if (inputs.any { it.isBlank() || it.length > properties.maxInputCharacters }) {
            throw RagEmbeddingInvalidResponseException("embedding input is blank or exceeds the configured size")
        }
        if (inputs.sumOf(String::length) > properties.maxTotalBatchCharacters) {
            throw RagEmbeddingInvalidResponseException("embedding input batch exceeds the configured total size")
        }
    }
}
