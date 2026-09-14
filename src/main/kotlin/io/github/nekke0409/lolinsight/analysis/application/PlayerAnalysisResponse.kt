package io.github.nekke0409.lolinsight.analysis.application

data class PlayerAnalysisResponse(
    val status: PlayerAnalysisResponseStatus,
    val analysis: PlayerAnalysisResult?,
) {
    init {
        if (status == PlayerAnalysisResponseStatus.ANALYZED) {
            requireNotNull(analysis) { "ANALYZED requires analysis" }
        } else {
            require(analysis == null) { "$status must not include analysis" }
        }
    }
}

enum class PlayerAnalysisResponseStatus {
    ANALYZED,
    INSUFFICIENT_COMPARISON_DATA,
    UNRANKED,
}
