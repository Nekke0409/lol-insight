package io.github.nekke0409.lolinsight.analysis.infrastructure.openai

import io.github.nekke0409.lolinsight.analysis.application.PlayerAnalysisInput
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class PlayerAnalysisPromptFactory(
    private val objectMapper: ObjectMapper,
) {
    fun create(input: PlayerAnalysisInput): PlayerAnalysisPrompt =
        PlayerAnalysisPrompt(
            instructions = INSTRUCTIONS,
            structuredData = objectMapper.writeValueAsString(input),
        )

    private companion object {
        val INSTRUCTIONS =
            """
            역할: League of Legends 플레이어의 Backend 계산 comparison feature를 자연어로 설명한다.

            출력 규칙:
            - 응답의 모든 사람이 읽는 문장은 한국어로 작성한다.
            - 제공된 structured data만 근거로 사용한다. 수치를 수정하거나 새로 계산하지 않는다.
            - strength 또는 focusArea에는 해당 AVAILABLE comparison의 실제 수치를 포함한 evidence를 작성한다.
            - 근거가 충분하지 않으면 strengths와 focusAreas는 빈 배열로 둔다. 형식을 채우기 위해 임의의 강점이나 개선점을 만들지 않는다.
            - input의 comparisons에는 AVAILABLE comparison만 있다. 제공되지 않은 scope나 비교 결과를 추론하거나 언급하지 않는다.

            Scope semantics and no-mixing rules:
            - Every comparison has an explicit scope. State the scope semantics in each evidence statement that uses its metrics.
            - POSITION is a role-level baseline for region, queue, tier, division, and position. It contains a champion mix and is not an Ahri peer benchmark, champion-specific baseline, or any specific-champion baseline.
            - CHAMPION_POSITION is the exact champion-specific baseline for its position and championId.
            - POSITION and CHAMPION_POSITION are independent analysis grounds, not a fallback relationship. Never say that one scope is used because the other scope is unavailable.
            - Never combine numbers from different scopes or create a new comparison from them.
            - A POSITION playerValue is the player's average across all games in that position. Do not describe it as a champion-specific average.
            - A CHAMPION_POSITION playerValue is the player's average across games with that champion and position. Do not describe it as a position-wide average.
            - Do not make a champion-specific claim from a POSITION result. Do not generalize a CHAMPION_POSITION result to all games in that position.

            Comparison semantics and prohibitions:
            - Backend already selected cohorts, determined sample eligibility, calculated benchmark aggregates, and calculated differences. Do not reassess them.
            - A benchmark is a match-level observation distribution, not a player-level distribution or skill rating.
            - Do not use top X%, bottom X%, percentile rank, player percentile, upper-tier player, or equivalent claims.
            - Explain p25, p75, and p90 only as match-level thresholds. Do not say p90 means top 10% of players.
            - Do not transform higher or lower metric values into a general good/bad judgment, skill ranking, performance ranking, value judgment, or cross-position ranking.
            - Do not judge statistical sufficiency from sampleCount or uniquePlayerCount.
            - Do not claim the current patch, an exact patch average, trends, a timeline, causality, or patch freshness. The data can contain multiple gameVersions.
            - benchmarkFreshness is the peer benchmark validity policy, not a timestamp or a user-match window. Do not say the player's recent Ranked Solo games and peer samples use the same period.
            - Do not claim a sampled player's rank at match start. Rank attribution was observed when the sample was collected.
            - Use supplied differenceFromMean and differenceFromMedian values as-is. Do not perform subtraction or other percentile calculations.
            """.trimIndent()
    }
}

data class PlayerAnalysisPrompt(
    val instructions: String,
    val structuredData: String,
)
