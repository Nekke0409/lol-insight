# OpenAI reasoning effort latency 실험 (2026-09-16)

## 목적

`/analysis`의 async job 도입 여부를 판단하기 전에, `gpt-5-mini` Responses API Structured Outputs의
reasoning token과 provider latency 관계를 한 변수만 바꿔 확인한다.

## 범위와 고정 조건

두 run은 `OpenAiPlayerAnalysisManualSmokeTest`의 결정적 fixture를 사용했다. fixture에는 `POSITION` 1개와
`CHAMPION_POSITION` 1개의 AVAILABLE comparison이 있고, model, prompt, Structured Output schema, timeout,
retry를 고정했다.

- configured model: `gpt-5-mini`
- actual response model: `gpt-5-mini-2025-08-07`
- timeout: process-local `OPENAI_TIMEOUT=90s` (production default는 계속 `60s`)
- retry: `0`
- baseline request: reasoning field 미전송 (model default)
- lower run: `OPENAI_REASONING_EFFORT=low`
- 외부 OpenAI 호출: 성공 run 각 조건 1회, 총 2회

Gradle은 이 환경 변수를 test input으로 추적하지 않으므로, baseline과 lower run을 같은 workspace에서 연속 실행할 때는
각 명령에 `--rerun-tasks`를 붙여야 실제 smoke가 다시 실행된다.

manual smoke fixture의 input은 1,040 tokens였다. 기존 실제 `/analysis` 관측 input 2,453 tokens보다 1,413
tokens(약 57.6%) 작으므로, 이 실험은 reasoning effort의 방향성과 원인 확인에는 사용하지만 실제 endpoint의
절대 latency SLA를 확정하지는 않는다.

## 결과

| 항목 | model default | `low` | 변화 |
| --- | ---: | ---: | ---: |
| provider latency | 41.261s | 22.794s | -18.467s (-44.8%) |
| manual generator elapsed | 41.598s | 23.087s | -18.512s (-44.5%) |
| input tokens | 1,040 | 1,040 | 0 |
| output tokens | 3,287 | 1,293 | -1,994 (-60.7%) |
| reasoning tokens | 2,048 | 192 | -1,856 (-90.6%) |
| visible output tokens (`output - reasoning`) | 1,239 | 1,101 | -138 (-11.1%) |
| total tokens | 4,327 | 2,333 | -1,994 (-46.1%) |

`output_tokens_details.reasoning_tokens`는 SDK가 제공한 output token의 상세 분해값이므로, 이 표의 visible
output은 `output - reasoning`으로 계산했다.

두 run 모두 Structured Output mapping, 한국어 summary, 필수 field completeness, 기존 top-X/player-percentile
guardrail 검증을 통과했다. fixture의 결과 배열 개수는 default에서 observations 2, strengths 2, focusAreas 0,
caveats 1이었고, `low`에서 observations 4, strengths 2, focusAreas 0, caveats 1이었다. scope semantics와 backend
숫자 왜곡은 변경하지 않은 prompt/structured input contract에 의존하며, raw response를 보관하지 않는 이 smoke만으로
별도의 의미론적 보증을 하지는 않는다. raw prompt, response, Riot identifier, API key는 기록하거나 저장하지 않았다.

## 해석과 다음 결정

baseline output의 62.3%가 reasoning tokens였고, `low`는 reasoning을 크게 줄이면서 visible output은 11.1%만
줄였다. 따라서 이 fixture에서는 output budget보다 reasoning effort가 latency와 token 사용량의 주된 원인이다.
이번 범위에서는 max output tokens, verbosity, schema, prompt를 변경하지 않는다.

`low`의 fixture latency는 synchronous API 가능성을 검토할 근거를 만들었지만, 실제 `/analysis` input이 더 크고
각 조건을 한 번만 실행했으므로 production reasoning policy나 async job 도입 여부를 확정하지 않는다. 다음 단계는
동일한 observability에서 representative `/analysis` target을 한 번 실행해 `low`의 latency와 quality contract를
확인하는 것이다. 그 결과도 30~60초 이상이면 async job 도입 근거가 강화된다.

production default timeout과 reasoning policy는 이 실험으로 변경하지 않았다.
