package io.github.nekke0409.lolinsight.analysis.application

fun interface PlayerAnalysisGenerator {
    fun generate(input: PlayerAnalysisInput): PlayerAnalysisResult
}
