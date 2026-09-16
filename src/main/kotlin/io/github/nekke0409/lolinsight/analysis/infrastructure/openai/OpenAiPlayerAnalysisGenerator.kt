package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.ResponsesModel
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.StructuredResponseCreateParams
import io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisAuthenticationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisConfigurationException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisGenerator
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInvalidResponseException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisProviderException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisRateLimitException
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisTransportException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.jvm.optionals.getOrNull

internal const val OPENAI_MAX_RETRIES = 0

@Component
internal class OpenAiPlayerAnalysisGenerator(
    private val properties: OpenAiProperties,
    private val promptFactory: PlayerAnalysisPromptFactory,
    @Autowired(required = false) private val clientOverride: OpenAIClient? = null,
    private val observationRecorder: OpenAiAnalysisObservationRecorder = NoOpOpenAiAnalysisObservationRecorder,
) : PlayerAnalysisGenerator {
    private val client: OpenAIClient by lazy {
        clientOverride
            ?: run {
                properties.requireConfigured()
                OpenAIOkHttpClient
                    .builder()
                    .apiKey(properties.apiKey)
                    .timeout(properties.timeout)
                    .maxRetries(OPENAI_MAX_RETRIES)
                    .build()
            }
    }

    override fun generate(input: PlayerAnalysisInput): PlayerAnalysisResult {
        var providerStartedAt: Long? = null

        return try {
            properties.requireConfigured()
            val prompt = promptFactory.create(input)
            val params =
                StructuredResponseCreateParams
                    .builder<OpenAiPlayerAnalysisOutput>()
                    .model(properties.model)
                    .instructions(prompt.instructions)
                    .input(prompt.structuredData)
                    .store(false)
                    .text(OpenAiPlayerAnalysisOutput::class.java)
                    .build()
            providerStartedAt = System.nanoTime()
            val response = client.responses().create(params)
            val result = response.toPlayerAnalysisResult()
            recordSuccess(
                model = response.model().toMetricModel(),
                latency = elapsedSince(providerStartedAt),
                usage = response.usage().getOrNull()?.toTokenUsage(),
            )
            result
        } catch (_: OpenAiConfigurationException) {
            recordFailure(OpenAiAnalysisFailureCategory.CONFIGURATION, providerStartedAt)
            throw PlayerAnalysisConfigurationException()
        } catch (exception: UnauthorizedException) {
            recordFailure(OpenAiAnalysisFailureCategory.AUTHENTICATION_PERMISSION, providerStartedAt)
            throw PlayerAnalysisAuthenticationException(exception)
        } catch (exception: PermissionDeniedException) {
            recordFailure(OpenAiAnalysisFailureCategory.AUTHENTICATION_PERMISSION, providerStartedAt)
            throw PlayerAnalysisAuthenticationException(exception)
        } catch (exception: RateLimitException) {
            recordFailure(OpenAiAnalysisFailureCategory.RATE_LIMIT, providerStartedAt)
            throw PlayerAnalysisRateLimitException(exception)
        } catch (exception: InternalServerException) {
            recordFailure(OpenAiAnalysisFailureCategory.UPSTREAM, providerStartedAt)
            throw PlayerAnalysisProviderException(exception)
        } catch (exception: OpenAIIoException) {
            recordFailure(OpenAiAnalysisFailureCategory.TIMEOUT_NETWORK, providerStartedAt)
            throw PlayerAnalysisTransportException(exception)
        } catch (exception: OpenAIServiceException) {
            recordFailure(OpenAiAnalysisFailureCategory.UPSTREAM, providerStartedAt)
            throw PlayerAnalysisProviderException(exception)
        } catch (exception: OpenAIInvalidDataException) {
            recordFailure(OpenAiAnalysisFailureCategory.MALFORMED_STRUCTURED_OUTPUT, providerStartedAt)
            throw PlayerAnalysisInvalidResponseException(exception)
        } catch (exception: PlayerAnalysisInvalidResponseException) {
            recordFailure(OpenAiAnalysisFailureCategory.MALFORMED_STRUCTURED_OUTPUT, providerStartedAt)
            throw exception
        } catch (exception: IllegalArgumentException) {
            recordFailure(OpenAiAnalysisFailureCategory.MALFORMED_STRUCTURED_OUTPUT, providerStartedAt)
            throw PlayerAnalysisInvalidResponseException(exception)
        }
    }

    private fun com.openai.models.responses.StructuredResponse<OpenAiPlayerAnalysisOutput>.toPlayerAnalysisResult(): PlayerAnalysisResult {
        val output =
            output()
                .asSequence()
                .mapNotNull { it.message().getOrNull() }
                .flatMap { it.content().asSequence() }
                .mapNotNull { it.outputText().getOrNull() }
                .singleOrNull()
                ?: throw PlayerAnalysisInvalidResponseException()

        return PlayerAnalysisResult(
            summary = requireNotNull(output.summary) { "summary is missing" },
            observations = output.observations.orEmpty().map { it.toAnalysisInsight() },
            strengths = output.strengths.orEmpty().map { it.toAnalysisInsight() },
            focusAreas = output.focusAreas.orEmpty().map { it.toAnalysisInsight() },
            caveats = output.caveats.orEmpty().map { requireNotNull(it) { "caveat is missing" } },
        )
    }

    private fun OpenAiAnalysisInsightOutput.toAnalysisInsight(): AnalysisInsight =
        AnalysisInsight(
            title = requireNotNull(title) { "insight title is missing" },
            explanation = requireNotNull(explanation) { "insight explanation is missing" },
            evidence = requireNotNull(evidence) { "insight evidence is missing" },
        )

    private fun ResponseUsage.toTokenUsage(): OpenAiTokenUsage =
        OpenAiTokenUsage(
            inputTokens = inputTokens(),
            outputTokens = outputTokens(),
            totalTokens = totalTokens(),
        )

    private fun ResponsesModel.toMetricModel(): String =
        when {
            isString() -> asString()
            isChat() -> asChat().asString()
            isOnly() -> asOnly().asString()
            else -> requestedModelTag()
        }.ifBlank(::requestedModelTag)

    private fun recordSuccess(
        model: String,
        latency: Duration,
        usage: OpenAiTokenUsage?,
    ) {
        try {
            observationRecorder.recordSuccess(model, latency, usage)
        } catch (_: Exception) {
            // Observability must not change the analysis result.
        }
    }

    private fun recordFailure(
        category: OpenAiAnalysisFailureCategory,
        providerStartedAt: Long?,
    ) {
        try {
            observationRecorder.recordFailure(
                model = requestedModelTag(),
                category = category,
                latency = providerStartedAt?.let(::elapsedSince),
            )
        } catch (_: Exception) {
            // Observability must not change the existing provider error mapping.
        }
    }

    private fun elapsedSince(startedAt: Long): Duration = Duration.ofNanos(System.nanoTime() - startedAt)

    private fun requestedModelTag(): String = properties.model.ifBlank { "unconfigured" }
}
