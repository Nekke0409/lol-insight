# Player Analysis v0.1

> [Player Analysis v0.2](player-analysis-v0.2.md)로 대체되었습니다. 이 문서는 이전의
> champion-position 전용 입력 계약을 기록합니다.

## 목적

`POST /api/v1/players/{gameName}/{tagLine}/analysis?start=0&count=20`는 Backend가 결정한
`PlayerComparisonFeature`를 OpenAI가 한국어로 설명하도록 만드는 최소 vertical slice다.
이 endpoint는 외부 AI 호출과 비용을 발생시킬 수 있으므로 `POST`를 사용한다.

## Pipeline과 analysis gate

```text
Riot API data
    -> normalized Match / player statistics
    -> PlayerComparisonFeatureService
    -> PlayerComparisonFeature
    -> AVAILABLE comparison gate
    -> PlayerAnalysisInput
    -> PlayerAnalysisGenerator
    -> OpenAI Responses API Structured Outputs
    -> PlayerAnalysisResult
```

`PlayerAnalysisService`는 comparison 중 `status == AVAILABLE`이 하나라도 있을 때만
`PlayerAnalysisGenerator`를 정확히 한 번 호출한다. cohort마다 별도 요청을 보내지 않는다.

- rank가 없으면 `UNRANKED`, `analysis: null`을 반환한다.
- rank는 있지만 AVAILABLE cohort가 없으면 `INSUFFICIENT_COMPARISON_DATA`, `analysis: null`을 반환한다.
- 두 경우 모두 OpenAI 호출 횟수는 0이다.
- AVAILABLE cohort와 unavailable cohort가 섞인 경우에는 AVAILABLE cohort만 분석하고,
  unavailable cohort는 `championId`, `position`, `userGames`, `status` 요약만 전달한다.

## 책임 분리

Backend가 cohort 선택, 사용자·benchmark 표본 적격성, benchmark aggregate, mean/median/p25/p75/p90,
`differenceFromMean`, `differenceFromMedian`을 계산한다. LLM은 이 결과를 자연어로 설명할 뿐이며,
원본 Riot Match JSON을 분석하거나 새 cohort·benchmark·수치·차이를 만들지 않는다.

`PlayerAnalysisGenerator`는 application의 provider-independent 경계다.
OpenAI SDK type, OpenAI response schema DTO, prompt와 provider 오류 해석은
`analysis/infrastructure/openai` 안에만 있다. 다중 provider factory나 strategy 계층은 아직 만들지 않는다.

## LLM input

`PlayerAnalysisInput`에는 다음만 들어간다.

- `availableComparisons`: exact cohort, `championId`, `position`, `userGames`, sample/unique player count,
  그리고 각 metric의 player value, benchmark mean/median, 이미 계산된 differences, p25/p75/p90
- `excludedComparisonSummary`: 분석 대상이 아닌 cohort의 최소 식별·status 정보
- `analysisLimitations`: match-level benchmark, patch/freshness, Backend availability policy 제약

PUUID, Riot ID, Match ID, raw Riot JSON, DB entity, Redis 값, API key, 내부 예외와 raw provider body는 전달하지 않는다.

## Structured result와 prompt 제약

`PlayerAnalysisResult`는 `summary`, `observations`, `strengths`, `focusAreas`, `caveats`로 구성한다.
각 insight는 `title`, `explanation`, `evidence`를 가진다. `strengths`와 `focusAreas`는 근거가 없으면 빈 배열일 수 있다.

OpenAI adapter는 Responses API의 Structured Outputs를 사용한다. SDK class-based schema 생성을 위해
OpenAI infrastructure 전용 Java DTO를 사용하고, 곧바로 application의 `PlayerAnalysisResult`로 변환한다.

Prompt는 instructions와 `PlayerAnalysisInput` JSON을 분리하며, 다음을 강제한다.

- 모든 사용자 대상 문장은 한국어
- supplied number를 변경하거나 재계산하지 않음
- top X%, bottom X%, player percentile, percentile rank, 상위권 player 표현 금지
- p25/p75/p90은 match-level threshold일 뿐 player percentile이 아님
- 임의의 good/bad, 실력 우열, metric 방향성, cross-position ranking 금지
- timeline, 현재 patch 기준 정확한 평균, freshness나 인과관계 추론 금지
- sample size의 충분성을 LLM이 재판단하지 않음

Benchmark v0.1은 peer player의 player-level aggregate가 아니라 exact cohort의 match-level observation
distribution이다. 또한 여러 `gameVersion` 표본이 섞일 수 있다.

## Configuration과 오류

```text
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-5-mini
OPENAI_TIMEOUT=20s
```

`OPENAI_MODEL`은 application/business code에 고정하지 않는다. 기본값 `gpt-5-mini`은 비용 민감한
structured explanation MVP를 위한 local default이며 환경 변수로 교체할 수 있다. API key가 비어 있어도
application startup과 Riot/benchmark 기능은 실패하지 않는다. 실제 analysis 요청에서만 명시적인
configuration error가 난다.

SDK client timeout은 `OPENAI_TIMEOUT`으로 제어하고 SDK retry는 `maxRetries(0)`으로 비활성화한다.
이 작업에서는 retry/backoff를 추가하지 않는다.

| Failure | HTTP contract |
| --- | --- |
| missing configuration | 503, `AI analysis is not configured.` |
| provider authentication / permission | 502, `AI analysis provider rejected the request.` |
| rate limit | 429, `AI analysis is temporarily rate limited.` |
| provider 5xx / unexpected provider status / structured response conversion | 502, `Unable to generate AI analysis.` |
| network / timeout | 503, `AI analysis provider is temporarily unavailable.` |

API response에는 raw provider body, stack trace, API key가 포함되지 않는다.

## 비용·저장 정책과 현재 제약

AI 결과 Redis cache, DB persistence, per-user AI rate limit, token/cost observability는 v0.1 범위 밖이다.
AVAILABLE analysis endpoint 요청은 매번 최대 한 번의 OpenAI 요청을 발생시킨다. Responses API request에는
`store=false`를 명시한다.

현재 local benchmark DB는 일반적으로 AVAILABLE cohort를 충분히 갖고 있지 않다. 따라서 실제 player endpoint가
OpenAI를 호출하지 않고 deterministic unavailable response를 반환하는 것은 정상이다. production threshold를 낮춰
connectivity를 확인하지 않는다.

실제 OpenAI 연결은 production endpoint 대신 opt-in manual smoke test로 확인한다.

```powershell
$env:OPENAI_API_KEY = "..."
$env:OPENAI_MODEL = "gpt-5-mini"
$env:RUN_OPENAI_SMOKE_TEST = "true"
.\gradlew.bat test --tests "*OpenAiPlayerAnalysisManualSmokeTest" --no-daemon
```

이 test는 deterministic fixture input만 사용하며, API key가 없거나 opt-in flag가 없으면 실행되지 않는다.

## Post-MVP

- AI result cache와 persistence
- per-user rate limit, token/cost observability
- patch-aware benchmark 및 실제 benchmark collection 확대
- result quality evaluation
- locale 지원
