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
import kotlin.jvm.optionals.getOrNull

@Component
class OpenAiPlayerAnalysisGenerator(
    private val properties: OpenAiProperties,
    private val promptFactory: PlayerAnalysisPromptFactory,
    @Autowired(required = false) private val clientOverride: OpenAIClient? = null,
) : PlayerAnalysisGenerator {
    private val client: OpenAIClient by lazy {
        clientOverride
            ?: run {
                properties.requireConfigured()
                OpenAIOkHttpClient
                    .builder()
                    .apiKey(properties.apiKey)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .build()
            }
    }

    override fun generate(input: PlayerAnalysisInput): PlayerAnalysisResult =
        try {
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
            val response = client.responses().create(params)
            response.toPlayerAnalysisResult()
        } catch (_: OpenAiConfigurationException) {
            throw PlayerAnalysisConfigurationException()
        } catch (exception: UnauthorizedException) {
            throw PlayerAnalysisAuthenticationException(exception)
        } catch (exception: PermissionDeniedException) {
            throw PlayerAnalysisAuthenticationException(exception)
        } catch (exception: RateLimitException) {
            throw PlayerAnalysisRateLimitException(exception)
        } catch (exception: InternalServerException) {
            throw PlayerAnalysisProviderException(exception)
        } catch (exception: OpenAIIoException) {
            throw PlayerAnalysisTransportException(exception)
        } catch (exception: OpenAIServiceException) {
            throw PlayerAnalysisProviderException(exception)
        } catch (exception: OpenAIInvalidDataException) {
            throw PlayerAnalysisInvalidResponseException(exception)
        } catch (exception: IllegalArgumentException) {
            throw PlayerAnalysisInvalidResponseException(exception)
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
}
