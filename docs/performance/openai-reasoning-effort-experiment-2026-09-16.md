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

## Representative `/analysis` text verbosity 실험

### 목적과 고정 조건

reasoning effort를 `low`로 고정한 representative 실제 `/analysis`에서 visible output 규모가 latency의 다음
주요 요인인지 한 번만 확인했다. `gpt-5-mini`, 동일 Riot target, `start=0`, `count=20`, 동일 benchmark DB,
prompt, Structured Output schema, retry `0`을 유지하고, process-local로만 다음 값을 적용했다.

- `OPENAI_REASONING_EFFORT=low`
- `OPENAI_TEXT_VERBOSITY=low`
- `OPENAI_TIMEOUT=90s`

`openai-java` 4.63.1은 Structured Outputs에서도 `StructuredResponseTextConfig`를 통해
`ResponseTextConfig.Verbosity.LOW`를 설정할 수 있다. 구현은 OpenAI infrastructure 안에만 있으며, 값이 없을
때는 기존 `text(OpenAiPlayerAnalysisOutput::class.java)` 경로를 그대로 사용해 `text.verbosity` field를
전송하지 않는다. `low` 외의 값은 기존 reasoning override와 동일하게 configuration error로 거부한다.

production YAML의 reasoning, verbosity, timeout 기본값은 바꾸지 않았다. `max_output_tokens`도 설정하지 않았다.
이는 Responses API의 output token budget이 visible output과 reasoning token을 함께 제한하므로, hard cap을
동시에 바꾸면 verbosity의 latency/quality 영향을 분리할 수 없고 Structured Output truncation 위험도 생기기
때문이다.

### 실제 결과

실행 중인 진단 프로세스의 Micrometer 값으로 provider metric 1건과 endpoint metric 1건을 수집했다. raw prompt,
response, Riot 식별자, Match ID, API key는 기록하지 않았다.

| Metric | Actual default | Actual low reasoning | Actual low reasoning + low verbosity |
| --- | ---: | ---: | ---: |
| Provider latency | 74.306s | 47.603s | 38.153s |
| Endpoint latency | 75.448s | 48.749s | 40.464s |
| Input tokens | 2,453 | 2,453 | 2,453 |
| Output tokens | 5,244 | 3,028 | 1,824 |
| Reasoning tokens | unknown | 448 | 192 |
| Visible output (`output - reasoning`) | unknown | 2,580 | 1,632 |
| Total tokens | 7,697 | 5,481 | 4,277 |

`actual-low` 대비 변화는 provider latency `-19.9%`, endpoint latency `-17.0%`, output tokens `-39.8%`,
visible output `-36.7%`, total tokens `-22.0%`다.

HTTP endpoint는 200을 반환했고, `ai.generation.requests`의 success가 1건이며 actual model은
`gpt-5-mini-2025-08-07`이었다. success 기록은 Structured Output mapping 뒤에만 발생하므로 mapping은
성공했다. `PlayerAnalysisService`는 provider를 호출한 성공 경로에서만 `ANALYZED` response를 만들며, output
mapper는 summary와 insight의 모든 필수 field, caveat의 non-null을 요구한다. 따라서 status/analysis와 필수
field contract도 유지됐다. prompt와 JSON schema는 변경하지 않았고, unit test는 verbosity 미설정 시 field
생략, `low`에서 reasoning과 verbosity의 동시 설정, 같은 Structured Output schema 보존을 검증한다.

### 해석과 다음 결정

verbosity를 낮추면 output과 visible output은 유의미하게 줄고 endpoint latency도 줄었다. 하지만 40.464초는
synchronous UX 목표로 보기에는 여전히 길다. 따라서 이번 결과는 production default 채택 여부를 결정하지
않으며, reasoning=`low`와 verbosity=`low`의 production 후보 평가는 별도 결정으로 남긴다. async analysis job
도입 검토는 계속 권장한다. 이 결과 뒤에는 `max_output_tokens`, prompt 축소, schema 축소를 추가로 반복 실험하지
않고 sync/async 구조 결정을 우선한다.
