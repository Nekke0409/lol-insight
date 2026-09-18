package io.github.nekke0409.lolinsight.analysis.application

/**
 * Reuses successful provider-independent analysis snapshots for the same effective LLM input.
 * Cache infrastructure failures are deliberately represented as a miss by implementations.
 */
interface PlayerAnalysisResultCache {
    fun find(input: PlayerAnalysisInput): PlayerAnalysisResult?

    fun store(
        input: PlayerAnalysisInput,
        result: PlayerAnalysisResult,
    )
}
