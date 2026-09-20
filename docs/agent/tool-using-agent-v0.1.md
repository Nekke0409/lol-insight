# Tool-using AI Agent v0.1

## Smoke 관측

`AGENT_SMOKE_OBSERVATION_ENABLED=true`는 local opt-in 관측기다. 선택된 Tool과 `groupBy`, 통계 Tool의 표본 수와 역할별 games·winRate·KDA·CS/min, comparison Tool의 scope·position·현재 tier/division·userGames·status·benchmark 표본 수를 기록한다. `AVAILABLE` comparison은 KDA와 CS/min의 playerValue·benchmarkMean·differenceFromMean만 추가로 기록한다. `OutputCaptureExtension` 기반 회귀 테스트는 실제 `record()` 로그에서 AVAILABLE 비교 상세값, 비교 불가 상태와 표본, 임의 Tool 이름 정규화 및 raw payload 비노출을 확인한다. 질문, Tool JSON, 최종 답변, 플레이어·champion 식별자, match ID, provider raw response, API key는 기록하지 않으며 기본값은 `false`다.

## 목적과 범위

Agent v0.1은 URL에 지정된 한 명의 플레이어에 대해 자연어 질문을 받고, 모델이 허용된 Backend Tool을 선택해
구조화된 결과를 받은 뒤 한국어 답변을 만드는 읽기 전용 기능이다. 대상 플레이어는 HTTP path에서만 정하며 모델은
다른 플레이어, Riot ID, PUUID, match ID, URL, SQL, DB table 또는 서버 함수를 Tool 인자로 지정할 수 없다.

분석 범위는 최근 최대 20개의 `Ranked Solo` 경기와 현재 확인된 동일 tier/division peer benchmark다.

- 역할별 최근 통계 요약
- 챔피언-역할별 최근 통계 요약
- 동일 tier/division benchmark 비교
- benchmark 표본 부족 또는 rank 부재 설명

기간 비교, 패치 추세, 성과 원인 단정, 특정 경기 전술 분석, 다른 플레이어 자동 탐색은 v0.1 범위가 아니다.
이 범위를 벗어난 질문은 지원 범위를 답변으로 설명하며, 존재하지 않는 Tool이나 데이터를 만들어 사용하지 않는다.

## HTTP 계약

기능은 기본적으로 비활성화되어 있다. 개인 local 검증에서만 `AGENT_ENABLED=true`로 명시적으로 켠다.

```text
POST /api/v1/players/{gameName}/{tagLine}/agent-questions
Content-Type: application/json

{"question":"최근 미드 통계와 같은 티어 비교를 요약해줘"}
```

`question`은 공백이 아니어야 하고 최대 1,000자다. 응답에는 최종 답변, 실제 실행한 Tool의 성공 여부와 호출 순서,
Backend가 확인한 데이터 한계, 그리고 loop 종료 이유가 포함된다. 모델의 내부 추론이나 provider raw response는
응답 또는 로그에 포함하지 않는다.

기능이 꺼져 있으면 `404`를 반환하며, quota와 모델 호출은 발생하지 않는다. Agent HTTP 요청은 기존
analysis generation rate limit을 한 요청당 한 번만 사용한다. loop 내부의 후속 모델 요청은 별도의 HTTP 요청이
아니므로 quota를 다시 소비하지 않는다.

## Tool 계약

두 Tool 모두 `strict: true` JSON Schema를 사용한다. 인자는 아래처럼 `groupBy` 하나만 포함해야 하며,
`POSITION`과 `CHAMPION_POSITION` 이외의 enum, 여분 필드, 잘못된 JSON은 dispatcher가 거절한다.

```json
{"groupBy":"POSITION"}
```

| Tool | 기존 Application Service | 결과 |
| --- | --- | --- |
| `get_ranked_stats` | `PlayerComparisonContextService` | 지정 scope의 최근 Ranked Solo 표본 수와 승률, KDA, CS/min 등 Backend 계산 통계 |
| `get_peer_comparison` | `PlayerComparisonFeatureService` | 현재 rank, 정확한 benchmark cohort, availability 상태와 AVAILABLE인 경우에만 metric comparison |

한 Agent 요청에서 `PlayerComparisonContextService`가 만든 context는 재사용한다. 따라서 두 Tool을 순차로 사용해도
동일한 Ranked Solo match history를 다시 로드하거나 통계 계산을 복사하지 않는다. `PlayerComparisonFeatureService`는
이 context를 받는 진입점을 제공하며 benchmark query만 수행한다.

Tool payload는 raw Riot JSON, Entity, PUUID, Riot ID, match ID, API key를 포함하지 않는다. `winRate`,
`killParticipation`, `damageShare`는 기존 Backend 계산값(0~1)을 그대로 전달하고 0~100 변환을 하지 않는다.
`csPerMinute`은 분당 CS이며, 나머지 분당 지표도 `metricUnits`가 명시한다.

`CHAMPION_POSITION`의 각 결과에는 분석에 필요한 `champion.championId`를 넣는다. 이는 플레이어 식별자가 아니며,
같은 역할에서 서로 다른 챔피언 결과를 구분하는 key다. 현재 계산 경로에 정확한 champion 이름은 없으므로 이름을
추정하거나 별도 Data Dragon/API 조회를 추가하지 않는다. `POSITION` 결과에는 불필요한 champion 정보가 없다.

예를 들어 통계 Tool의 결과는 다음처럼 조회 범위, 실제 분석 표본, 단위와 champion 식별을 함께 전달한다.

```json
{
  "scope": "CHAMPION_POSITION",
  "sample": {"requestedMatchCount": 20, "analyzedMatchCount": 6},
  "statistics": [
    {"scope": "CHAMPION_POSITION", "position": "MIDDLE", "champion": {"championId": 103}, "games": 5, "winRate": 0.6, "averageCsPerMinute": 7.4}
  ],
  "metricUnits": {"winRate": "ratio_0_to_1", "csPerMinute": "cs_per_minute"}
}
```

비교 Tool도 같은 champion 식별을 `comparisons[].champion`에 보존한다. `metrics`는 comparison `status`가
`AVAILABLE`일 때만 존재한다. `BENCHMARK_INSUFFICIENT_SAMPLE` 같은 상태에는 같은 tier/division의 실제 표본 수만
있을 수 있고 평균, 대체 tier, percentile 또는 임의 비교값은 없다.

`get_peer_comparison`은 `AVAILABLE`, `UNRANKED`, `INSUFFICIENT_USER_SAMPLE`, `BENCHMARK_NO_DATA`,
`BENCHMARK_INSUFFICIENT_SAMPLE`을 그대로 보존한다. 표본 부족 결과에 평균, 대체 tier, percentile 또는 임의
비교값을 넣지 않는다. 예를 들어 `EMERALD IV` benchmark가 부족할 때 `GOLD I` 결과로 대체하지 않는다.

## 실행 경계와 비용 제한

Agent loop는 model response → 단일 Tool call 검증/dispatcher → Tool result → 후속 model response 순서로
실행한다. OpenAI Responses API의 function call `call_id`와 `function_call_output`을 연결하고, reasoning item을
포함한 이전 response item을 후속 input에 다시 넣는다. `parallel_tool_calls=false`이므로 정상 turn은 Tool을 0개
또는 1개만 요청한다.

- 모델 요청: 최대 3회(최종 답변 요청 포함)
- Tool 실행 시도: 최대 2회
- 동일 Tool과 동일 JSON 인자 반복: 실행 차단
- 질문/Tool result 길이와 모델 output token: Backend 설정값으로 제한
- 각 provider 요청: 남은 전체 deadline과 per-request timeout 중 더 짧은 timeout 적용
- provider retry: 없음 (`maxRetries(0)`)
- response 저장: `store(false)`

마지막 허용 모델 요청 또는 Tool 예산 소진 뒤의 모델 요청은 `tool_choice=none`으로 Tool 선택을 막는다. 그래도 Tool
call이 오면 dispatcher를 실행하지 않고 제한 상태로 끝낸다. 따라서 `maxModelRequests=1` 또는 `2`처럼 더 작은
설정에서도 마지막 요청이 데이터 조회를 새로 시작하지 않는다.

전체 deadline은 provider뿐 아니라 Tool dispatcher에도 적용한다. Tool은 queue가 없는 단일 실행 경계에서 남은 시간만
기다리며, 만료 시 Future 취소와 interrupt를 요청하고 Agent는 후속 Tool·모델 요청을 시작하지 않는다. 이 경계는
Agent의 대기 중단과 thread 누적 방지를 위한 것이며, 이미 시작된 Riot HTTP 요청의 실제 취소를 보장하지는 않는다.
Riot client 자체의 connect/read timeout과 이 Agent deadline은 별개다. 이것은 Agent Job queue나 비동기 작업 영속화를
추가한 것이 아니다.

한 model turn에서 복수 Tool call이 오거나 제한을 넘기면 추가 Tool을 실행하지 않고 제한 상태로 종료한다. 잘못된
인자, 중복 호출, 실패한 Tool 시도도 Tool 예산에 포함하며 provider 자동 retry는 없다. Riot cooldown과 Riot/provider
오류는 기존 error boundary를 그대로 통과한다.

Agent 답변 cache, 대화 memory, Agent DB, job queue, 전역 Tool result cache는 v0.1에 추가하지 않는다. 기존
analysis result cache 역시 Agent 답변에 재사용하지 않는다.

## OpenAI SDK 경계

`AgentModelGateway`는 application의 provider-independent port다. OpenAI SDK type, Responses request/response,
function schema와 raw provider error는 `agent/infrastructure/openai` 안에 머문다. 기존
`OpenAiPlayerAnalysisGenerator`의 structured-output prompt, schema, cache, metric 계약은 변경하지 않는다.

현재 SDK 계약은 [OpenAI Function Calling 문서](https://developers.openai.com/api/docs/guides/function-calling)를
따른다. strict schema는 각 object에서 `additionalProperties: false`와 모든 property의 required 선언을 요구한다.

`store(false)`의 stateless continuation은 최초 user input, 이전 response의 reasoning/function-call/message item과
`call_id`가 일치하는 `function_call_output`을 순서대로 다시 보낸다. 응답 최상위 상태가 `completed`가 아니거나,
provider error, refusal, 빈 최종 답변이면 Tool 인자를 실행하거나 완료 답변으로 표시하지 않는다. incomplete의
`max_output_tokens`, `max_messages`, `content_filter`, `steered` 사유는 허용된 코드로 보존하며 자동 재호출하지도 않는다.

요청 단위의 안전한 실행 요약은 모델 요청 수, Tool 시도 수, 허용 Tool 이름과 성공/실패, 종료 사유, 지연 시간,
provider가 실제로 제공한 input/output/total token usage만 기록한다. usage가 없으면 0으로 추정하지 않고 생략한다.
질문 본문, Tool payload 전체, Riot/플레이어 식별자, API key, raw provider response와 최종 답변 본문은 기록하지 않는다.

## 검증 경계

기본 test/build/CI는 실제 Riot 또는 OpenAI를 호출하지 않는다. scripted `AgentModelGateway`로 loop와 dispatcher
연결을 검증하고, 최소 연결 test는 실제 `PlayerComparisonContextService`/Builder와
`PlayerComparisonFeatureService`를 지나 EMERALD IV의 benchmark 부족 결과와 champion-position 식별을 검증한다.
Riot match loading, rank lookup, benchmark repository와 OpenAI만 대역으로 둔다. deadline boundary, 마지막 요청의
Tool 금지, invalid/duplicate Tool 인자, incomplete provider response와 HTTP 기본 비활성화도 별도로 검증한다.
이 검증은 실제 모델의 질문 이해, Tool 선택 품질, 최종 한국어 답변 품질을 보증하지 않는다.

실제 smoke는 provider key, `AGENT_ENABLED=true`, 해당 플레이어의 최근 Ranked Solo match, rate-limit 여유와 필요한
동일 tier/division benchmark 표본을 준비한 local opt-in 범위에서만 수행한다. benchmark가 없으면 stats Tool과 limitation
답변은 확인할 수 있지만 peer comparison 수치가 있는 `AVAILABLE` 응답은 확인할 수 없다.

2026-09-21에는 통계 Tool 1회와 비교 Tool 1회를 연속 선택한 실제 smoke가 1회 수행됐다. 모델 요청 3회, Tool 실행 2회,
HTTP 200과 `COMPLETED`, 통계 Tool 값과 최종 답변의 대조, 그리고 다른 tier 대체·percentile·원인 추정이 없는 비교 불가
설명까지 확인했다. 실행 당시 detailed comparison logger는 format 인자 누락으로 comparison status·benchmark 표본·metric을
출력하지 않았다. 따라서 같은 실행의 comparison Backend 값과 최종 답변을 직접 대조하거나 `AVAILABLE` 수치 정확성을 확인한
것으로 취급하지 않는다. 이후 수정한 logger 출력은 외부 호출 없는 `OutputCaptureExtension` 회귀 테스트로 검증했으며, 이 사실은
과거 실제 smoke에서 수정 후 상세 로그를 관측했다는 뜻이 아니다. 상세 실행 기록은
[`2026-09-21 Agent POSITION 비교 smoke 검증 기록`](2026-09-21-agent-position-comparison-smoke.md)을 따른다.
