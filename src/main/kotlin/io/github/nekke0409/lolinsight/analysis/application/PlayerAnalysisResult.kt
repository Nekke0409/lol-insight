package io.github.nekke0409.lolinsight.analysis.application

data class PlayerAnalysisResult(
    val summary: String,
    val observations: List<AnalysisInsight>,
    val strengths: List<AnalysisInsight>,
    val focusAreas: List<AnalysisInsight>,
    val caveats: List<String>,
)

data class AnalysisInsight(
    val title: String,
    val explanation: String,
    val evidence: String,
)
