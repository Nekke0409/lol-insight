# Player Analysis v0.2

## 목적

`POST /api/v1/players/{gameName}/{tagLine}/analysis?start=0&count=20`는 Backend가 계산한
`PlayerComparisonFeature`를 한국어 자연어 분석으로 변환한다. 성공한 요청은 외부 AI provider 호출 비용을 발생시킬 수 있으므로
endpoint는 계속 `POST`를 사용한다.

이 버전은 ADR-008에서 도입한 두 독립 benchmark scope에 맞춰 OpenAI 입력 경계를 확장한다. benchmark 수집,
aggregate SQL, availability threshold, self-exclusion, REST output schema는 변경하지 않는다.

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

## OpenAI Observability v0.1

OpenAI 어댑터는 이 request/response contract를 바꾸지 않고 best-effort Micrometer 계측을 추가한다.
`PlayerAnalysisResult`와 `PlayerAnalysisResponse`는 provider-independent를 유지하며, token usage와 provider
request identifier를 caller에게 노출하지 않는다.

각 생성 결과마다 `ai.generation.requests`는 `provider=openai`, `model`, `outcome`, `error_category`를
기록한다. `ai.generation.duration`은 호출이 `responses.create(...)`에 도달한 뒤 같은 tag로 provider 호출
지연 시간을 기록한다. `ai.generation.tokens` counter는 SDK response에 optional `usage()`가 있을 때만
`token_type=input|output|total`을 기록한다. 성공한 호출은 실제 response model을, 실패한 호출은 configured
model을 사용한다.

success는 Responses API 호출 완료와 유효한 구조화 출력 매핑을 모두 요구한다. failure category는
`configuration`, `authentication_permission`, `rate_limit`, `upstream`, `timeout_network`,
`malformed_structured_output`이다. usage 누락은 failure가 아니다. configuration 실패는 OpenAI 요청을 보내지
않으므로 provider 호출 timer가 없다. 기존 error mapping과 analysis behavior는 바꾸지 않는다.

metric label은 의도적으로 low cardinality로 유지한다. Riot ID, PUUID, match ID, champion ID, request ID,
API key는 제외한다. 계측은 raw prompt, request/response JSON, 전체 analysis text, raw usage object도
log하거나 persist하지 않는다. v0.1은 currency cost를 계산하지 않는다. source of truth는 token count뿐이며
price/version/currency 정책은 이후로 미룬다.

recorder error는 분석 생성과 격리한다. 이 범위에는 외부 monitoring 호출, metrics exporter, Actuator 노출
설정, retry, cache, background analysis behavior를 추가하지 않는다. 전체 endpoint 지연 시간은 계속 framework
HTTP observation을 사용한다.

## 분석 게이트와 요청 횟수

서비스는 scope와 관계없이 하나 이상의 comparison이 `status == AVAILABLE`이면
`PlayerAnalysisGenerator`를 정확히 한 번 호출한다. AVAILABLE comparison이 없으면 생성기를 호출하지 않는다.

| POSITION 범위 | CHAMPION_POSITION 범위 | 결과 |
| --- | --- | --- |
| `AVAILABLE` | 표본 부족 또는 데이터 없음 | POSITION comparison만 포함한 요청 1회 |
| 표본 부족 또는 데이터 없음 | `AVAILABLE` | CHAMPION_POSITION comparison만 포함한 요청 1회 |
| `AVAILABLE` | `AVAILABLE` | 두 comparison을 포함한 요청 1회 |
| `AVAILABLE` 아님 | `AVAILABLE` 아님 | `INSUFFICIENT_COMPARISON_DATA`, 요청 없음 |

rank가 없는 사용자는 계속 `UNRANKED`, `analysis: null`을 받고 AI provider 요청은 없다. v0.2에는
scope별 요청, fallback 요청, cache, token optimizer, 분석 결과 저장을 추가하지 않는다.

## 구조화된 입력

`PlayerAnalysisInput.comparisons`에는 `AVAILABLE` comparison만 들어간다. 순서는 결정적이며
`POSITION`이 먼저, `CHAMPION_POSITION`이 뒤이며, 같은 scope 안에서는 기존의 결정적인 comparison 순서를
유지한다. 사용 불가 comparison은 metric 근거를 포함해 OpenAI에 전달하지 않는다.

각 `AnalysisComparisonInput`에는 Backend가 계산한 다음 필드가 들어간다.

| 필드 | 의미 |
| --- | --- |
| `scope` | 명시적인 `POSITION` 또는 `CHAMPION_POSITION` 모집단 식별자 |
| `position` | 사용자 통계와 benchmark cohort가 함께 사용하는 포지션 |
| `championId` | `POSITION`에서는 `null`, `CHAMPION_POSITION`에서는 필수 |
| `userGames` | 같은 scope에서의 사용자 관측 수 |
| `benchmarkCohort` | region, queue, tier, division, position 및 일치하는 nullable champion identity |
| `benchmarkSampleCount`, `benchmarkUniquePlayerCount` | 대상 플레이어 self-exclusion 후의 benchmark 수 |
| `metrics` | Backend가 계산한 player value, benchmark mean/median/p25/p75/p90, mean/median 차이 |

모델은 scope와 champion identity 조합이 유효한지, 중첩된 benchmark cohort가 입력의 position 및 champion
identity와 일치하는지 검증한다. 따라서 POSITION 입력에는 champion ID가 들어갈 수 없고,
CHAMPION_POSITION 입력은 champion ID를 생략할 수 없다.

`PlayerAnalysisInput`에는 PUUID, Riot ID, Match ID, raw Riot JSON, DB entity, Redis value, API key가 없다.
target PUUID는 Backend의 aggregate self-exclusion 경계 안에서만 사용한다.

## Scope의 의미

`POSITION`과 `CHAMPION_POSITION`은 fallback 관계가 아닌 독립적인 분석 근거다.

- `POSITION`은 `region / queue / tier / division / position` key를 사용하는 역할 수준의 경기 단위 기준선이다.
  이 scope의 사용자 값은 해당 position의 모든 사용자 경기 평균이며, benchmark에는 champion mix가 포함된다.
  Ahri를 포함한 특정 champion benchmark나 champion별 기준선으로 표현하면 안 된다.
- `CHAMPION_POSITION`은 `region / queue / tier / division / position / championId` key를 사용하는
  champion별 경기 단위 기준선이다. 이 scope의 사용자 값은 해당 champion과 position 경기만의 평균이다.

AI prompt는 metric을 사용하는 각 evidence 문장이 scope 의미를 명시하도록 요구한다. POSITION 결과로
champion-specific 성과를 주장하거나, CHAMPION_POSITION 결과를 해당 position 전체 성과로 일반화하거나,
서로 다른 scope 숫자를 섞거나, 한 scope가 다른 scope를 대체했다고 표현하는 것을 금지한다.

## 계산과 출력 경계

Backend는 provider 호출 전에 모든 metric comparison을 계속 계산한다.

- `playerValue`
- `benchmarkMean`, `benchmarkMedian`, `benchmarkP25`, `benchmarkP75`, `benchmarkP90`
- `differenceFromMean`, `differenceFromMedian`
- `benchmarkSampleCount`, `benchmarkUniquePlayerCount`

OpenAI adapter는 뺄셈, percentile 계산, cohort 선택, eligibility 판단을 수행하지 않는다. prompt는
top-X-percent 주장, player percentile, 일반적인 좋음/나쁨 판정, cross-position ranking, patch-aware 주장,
Backend 제공 숫자 변경도 계속 금지한다.

`PlayerAnalysisResult`와 REST `PlayerAnalysisResponse` schema는 변경하지 않는다. 필요하면 evidence 텍스트에서
scope를 표현할 수 있으므로 출력 model을 새로 설계할 필요가 없다.

## 수동 smoke test

명시적으로 활성화한 OpenAI smoke test는 POSITION comparison 하나와 CHAMPION_POSITION comparison 하나가 있는 결정적인 입력을
사용한다. 다음 두 환경 변수가 모두 있어야 실행되며, 그렇지 않으면 skip된다.

```powershell
$env:OPENAI_API_KEY = "..."
$env:OPENAI_MODEL = "gpt-5-mini"
$env:RUN_OPENAI_SMOKE_TEST = "true"
.\gradlew.bat test --tests "*OpenAiPlayerAnalysisManualSmokeTest" --no-daemon
```

manual smoke는 production `OpenAiProperties`와 동일한 Spring Boot binding으로 `OPENAI_TIMEOUT`을 읽는다.
다만 manual smoke의 `StandardEnvironment` + `Binder`는 `application.yaml`의 model 기본값을 자동으로 읽지 않으므로,
smoke에서는 현재처럼 `OPENAI_MODEL`을 명시한다. production Spring Boot runtime은 `application.yaml` 기본값과
환경 변수 override를 함께 사용한다.

실제 Responses API smoke에서 20초 timeout은 약 21.4~21.5초에 반복 timeout됐고, 45초에서는 약
38.258~43.469초에 성공했다. 따라서 MVP의 operational default timeout은 latency SLA가 아니라 이 실측에
운영상 여유를 더한 `60s`다. `OPENAI_TIMEOUT`으로 필요한 환경에서 override할 수 있다. retry는 비용과
중복 요청을 제어하기 위해 `maxRetries(0)`을 계속 유지한다.

```powershell
$env:OPENAI_TIMEOUT = "45s"
```

smoke test는 운영 분석 정책을 임의로 변경하지 않는다.
