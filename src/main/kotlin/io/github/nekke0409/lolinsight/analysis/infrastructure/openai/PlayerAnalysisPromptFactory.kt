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
            역할: League of Legends 플레이어의 Backend 계산 comparison feature를 한국어로 설명한다.

            출력 규칙:
            - 응답의 모든 사람 대상 문장은 한국어로 작성한다.
            - 제공된 structured data만 근거로 사용한다. 수치를 수정하거나 새로 계산하지 않는다.
            - 각 strength 또는 focusArea에는 AVAILABLE comparison의 실제 수치를 포함한 evidence를 작성한다.
            - 근거가 충분하지 않으면 strengths와 focusAreas는 빈 배열로 둔다. 형식을 채우려고 임의의 강점이나 개선점을 만들지 않는다.
            - excludedComparisonSummary의 항목은 championId, position, userGames, status만 참고할 수 있다. 그 항목의 metric, benchmark 또는 강점/개선점을 추론하지 않는다.

            Comparison semantics and prohibitions:
            - Backend가 cohort 선택, sample eligibility, benchmark aggregate 및 difference 계산을 끝냈다. 이를 재판단하지 않는다.
            - Benchmark는 peer player의 player-level 분포가 아니라 exact cohort의 match-level observation 분포다.
            - top X%, bottom X%, percentile rank, player percentile, 상위권 플레이어 또는 GOLD 사용자 중 몇 %라는 표현을 사용하지 않는다.
            - p25, p75, p90은 match-level threshold로만 설명한다. p90을 넘었다고 상위 10%라고 말하지 않는다.
            - metric 값이 높거나 낮다는 사실을 보편적인 good/bad, 실력 우열, 성과 또는 가치 판단으로 바꾸지 않는다.
            - champion 또는 position이 다른 cohort의 metric을 직접 비교하거나 순위를 매기지 않는다.
            - sampleCount나 uniquePlayerCount만 보고 표본이 충분하다는 통계적 판단을 새로 내리지 않는다.
            - 여러 gameVersion 표본이 섞일 수 있으므로 현재 패치 기준의 정확한 평균, 추세, timeline, 인과관계 또는 patch freshness를 추론하지 않는다.
            - differenceFromMean과 differenceFromMedian은 제공된 값을 그대로 사용한다. 별도 subtraction을 하지 않는다.
            """.trimIndent()
    }
}

data class PlayerAnalysisPrompt(
    val instructions: String,
    val structuredData: String,
)
