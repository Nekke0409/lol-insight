# ADR-017: Bounded Tool-using Agent 경계 사용

Status: Accepted

## 배경

기존 Backend는 최근 Ranked Solo 경기 통계와 exact peer benchmark comparison을 결정적으로 계산한다.
사용자의 자연어 질문에 맞춰 이 결과를 선택해 설명할 필요가 있지만, 모델이 Riot HTTP client, repository,
Redis 또는 raw provider SDK를 직접 다루면 보안 경계와 통계 의미가 약해진다.

## 결정

Agent v0.1은 `AgentModelGateway`와 `AgentToolExecutor`의 작은 application 경계를 둔다. 모델은
`get_ranked_stats`, `get_peer_comparison` 두 function Tool만 선택할 수 있고, dispatcher가 strict schema와
서버 고정 대상 범위를 다시 검증한다.

Tool은 `PlayerComparisonContextService`와 `PlayerComparisonFeatureService`를 감싼다. 동일 요청의 context를
재사용해 match loading을 중복하지 않으며, 통계/benchmark 계산은 Agent 패키지에 복제하지 않는다.

OpenAI 구현은 Responses API function calling을 사용하고 `call_id`에 Tool output을 연결한다. `store(false)` 요청은
reasoning을 포함한 이전 response item을 유지하고, `completed`가 아닌 응답·refusal·빈 답변은 완료로 취급하지 않는다.
loop는 최대 모델 요청 3회, Tool 실행 시도 2회, 순차 Tool call, request deadline과 retry 없음으로 제한한다. 마지막
허용 모델 요청 또는 Tool 예산 소진 뒤에는 `tool_choice=none`을 강제한다.

전체 deadline은 provider 요청과 Tool dispatcher 대기에 함께 적용한다. Tool 호출은 queue 없는 single-thread
interrupt boundary에서 남은 시간만 기다린다. timeout은 Agent의 대기 및 후속 호출을 중단하지만 이미 시작한 외부 HTTP
요청의 실제 취소를 약속하지 않는다. 기능은 기본 비활성화하고 Agent memory, persistence, queue, answer cache는
추가하지 않는다.

## 결과와 trade-off

- LLM은 계산이 아닌 Tool 선택과 자연어 설명에 집중하며, raw Riot 식별자와 infrastructure 접근이 Tool payload로
  새지 않는다.
- benchmark 표본 부족 상태와 scope semantics를 구조화된 상태로 보존한다.
- `CHAMPION_POSITION`의 `championId`는 분석 결과 식별자로 제공해 같은 role의 챔피언 결과를 구분한다. player 식별자나
  추정한 champion 이름은 제공하지 않는다.
- 한 요청의 model call 비용은 제한되지만, 실제 모델이 첫 두 Tool을 부적절하게 선택하면 후속 탐색을 계속하지
  않고 제한 상태로 끝난다.
- in-process request context 재사용은 global cache나 distributed single-flight가 아니다. 동시 요청과 process
  restart 사이의 결과 공유를 제공하지 않는다.
- 실제 모델 Tool 선택/답변 품질은 scripted test가 아니라 별도 opt-in smoke로 확인해야 한다.
- 안전한 실행 요약은 호출 수·허용 Tool 결과·종료 사유·지연 시간·제공된 token usage만 남긴다. provider가 usage를
  생략하면 0으로 추정하지 않는다.

## 검토한 대안

### 모델이 keyword if/else로 Tool을 선택

사용자 표현의 변형을 처리하지 못하고 Agent loop/function-call contract를 검증하지 못하므로 선택하지 않았다.

### LangChain, LangGraph, Agents framework 또는 MCP server 도입

두 개의 읽기 전용 Backend Tool과 bounded loop를 해결하는 데 필요한 복잡도보다 넓은 framework 경계를 만들므로
도입하지 않았다.

### RAG 또는 Vector DB 도입

현재 질문은 structured gameplay data 조회이므로 기존 Application Service와 Tool 결과가 source of truth다.
비정형 문서 검색 요구가 확인될 때 별도로 결정한다.
