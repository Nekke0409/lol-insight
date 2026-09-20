# Agent POSITION 비교 smoke 검증 기록

## 실행 범위

- 실행 시각: 2026-09-21 01:36 (Asia/Seoul)
- 대상: 기존 local 설정의 대상 플레이어 (식별자는 이 문서에 기록하지 않음)
- 질문 범위: 최근 Ranked Solo `TOP` 통계와 현재 tier/division의 `POSITION/TOP` peer benchmark 비교
- HTTP 호출: `POST /api/v1/players/{gameName}/{tagLine}/agent-questions` 1회
- Agent 설정: `AGENT_ENABLED=true`, `AGENT_SMOKE_OBSERVATION_ENABLED=true`
- 명시적 비활성화: Automation, Automation bootstrap, benchmark replenishment/run-once, 기존 manual smoke/seed/poll flags

이 문서는 실제 질문과 최종 답변 원문, Tool JSON, 플레이어/PUUID/match ID, API key, provider raw response를 기록하지 않는다.

## 실제 관측 결과

Agent는 실제 모델 선택으로 다음 Tool을 순서대로 각각 한 번 실행했다.

1. `get_ranked_stats(groupBy=POSITION)` — success
2. `get_peer_comparison(groupBy=POSITION)` — success

실행 요약은 model request 3회, Tool execution 2회, `COMPLETED`, 12,303 ms였다. provider usage는 각 요청 순서대로
`input/output/total = 646/48/694`, `1166/21/1187`, `1277/411/1688`로 제공됐다.

통계 Tool 관측값은 요청 경기 20개, 분석 경기 20개였고 `TOP`은 18경기, 승률 0.5, 평균 KDA 2.7148368606701943,
평균 CS/min 7.773147676805781이었다. 최종 답변은 이를 9승 9패, 승률 50.0%로 표현했다.

최종 답변은 현재 `EMERALD IV`의 `TOP` peer benchmark에 표본이 없어 비교 수치·percentile을 제시하지 않았고,
다른 tier/division으로 대체하거나 성적 원인을 추정하지 않았다. 따라서 이번 실행은 comparison 불가 설명 경로의 성공으로 분류한다.

## 관측 한계와 후속 코드 수정

실행 시점의 smoke logger는 comparison 관측 객체를 생성했지만 logger format 인자에 전달하지 않는 누락이 있었다. 따라서 이 한 번의
실행 로그에는 `get_peer_comparison`의 success와 `POSITION` 선택만 남았고, comparison status·benchmark 표본 수·metric 값은
직접 관측되지 않았다. 이 사실을 근거 없이 보완하거나 두 번째 실제 Agent 요청으로 재시도하지 않았다.

실행 뒤 logger가 comparison 관측도 출력하도록 수정했다. 수정된 관측기는 scope·position·tier/division·userGames·status·benchmark
sample/unique-player 수와 `AVAILABLE`인 KDA/CS/min의 playerValue·benchmarkMean·differenceFromMean만 기록한다. 이 변경은
`clean ktlintCheck test build`와 74개 테스트(실패 0)로 검증했지만, 새 logger 출력 자체는 실제 provider smoke로 재검증하지 않았다.

구현상 Tool 결과는 다음 모델 요청의 `function_call_output`으로 전달된다. 이번 실행의 3 model request와 2 Tool execution은 이
bounded loop를 통과한 사실을 보여 주지만, raw Tool payload 및 provider request를 보관하지 않는 정책상 각 필드의 전달 자체를
로그로 독립 증명하지는 않는다.

## 데이터 보호와 정리

실행 전후 protected DB table 행 수는 동일했다.

| 테이블 | 실행 전 | 실행 후 |
| --- | ---: | ---: |
| `analysis_job` | 2 | 2 |
| `automation_execution` | 1 | 1 |
| `benchmark_replenishment_cursor` | 1 | 1 |
| `benchmark_sample` | 146 | 146 |
| `tracked_player_automation` | 1 | 1 |

Redis는 ephemeral cache이며 실행 뒤 `db0`에 51개의 TTL key가 관측됐다. 이 값은 업무 데이터 변경으로 해석하지 않았고,
실행 전 key-by-key 상태는 보관하지 않았다. Agent와 함께 기동한 PostgreSQL/Redis 및 localhost application process는 모두
원래의 중지 상태로 복구했다. 별도 Riot/OpenAI 요청, benchmark 수집, Automation 실행, DB 초기화 또는 volume 삭제는 없었다.

## 검증하지 않은 범위

- `AVAILABLE` peer comparison의 실제 KDA/CS/min 수치 일치
- 새 detailed comparison logger의 실제 provider smoke 출력
- 다른 질문, champion-position scope, 동시 요청, 다계정 또는 공개 배포 환경
