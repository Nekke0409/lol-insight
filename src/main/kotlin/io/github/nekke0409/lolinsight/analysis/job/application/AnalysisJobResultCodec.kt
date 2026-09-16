package io.github.nekke0409.lolinsight.analysis.job.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.nekke0409.lolinsight.analysis.application.AnalysisInsight
import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisResult
import org.springframework.stereotype.Component

@Component
class AnalysisJobResultCodec {
    private val objectMapper = ObjectMapper()

    fun write(result: PlayerAnalysisResult): JsonNode = objectMapper.valueToTree(result)

    fun read(result: JsonNode): PlayerAnalysisResult =
        PlayerAnalysisResult(
            summary = text(result, "summary"),
            observations = insights(result, "observations"),
            strengths = insights(result, "strengths"),
            focusAreas = insights(result, "focusAreas"),
            caveats = strings(result, "caveats"),
        )

    private fun insights(
        result: JsonNode,
        fieldName: String,
    ): List<AnalysisInsight> =
        array(result, fieldName).map { insight ->
            AnalysisInsight(
                title = text(insight, "title"),
                explanation = text(insight, "explanation"),
                evidence = text(insight, "evidence"),
            )
        }

    private fun strings(
        result: JsonNode,
        fieldName: String,
    ): List<String> = array(result, fieldName).map { value -> text(value) }

    private fun array(
        result: JsonNode,
        fieldName: String,
    ): List<JsonNode> {
        val value = required(result, fieldName)
        require(value.isArray) { "$fieldName must be an array" }
        return value.toList()
    }

    private fun text(
        node: JsonNode,
        fieldName: String? = null,
    ): String {
        val value = fieldName?.let { required(node, it) } ?: node
        require(value.isTextual) { "Analysis job result must contain text" }
        return value.textValue()
    }

    private fun required(
        node: JsonNode,
        fieldName: String,
    ): JsonNode = requireNotNull(node.get(fieldName)) { "Analysis job result is missing $fieldName" }
}
