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

최종 답변은 현재 `EMERALD IV`의 `TOP` peer benchmark에 표본이 없다고 설명하며 비교 수치·percentile을 제시하지 않았고,
다른 tier/division으로 대체하거나 성적 원인을 추정하지 않았다. 이는 최종 답변의 내용과 범위 준수에 대한 확인이며, 이 문장만으로
같은 실행의 Backend benchmark 표본 수 또는 comparison status를 독립 확인한 것은 아니다. 따라서 이번 실행은 두 Tool 실행과
비교 불가 설명의 최종 응답 생성이 성공한 경우로 기록한다.

## 관측 한계와 후속 코드 수정

실행 시점의 smoke logger는 comparison 관측 객체를 생성했지만 logger format 인자에 전달하지 않는 누락이 있었다. 따라서 이 한 번의
실행 로그에는 `get_peer_comparison`의 success와 `POSITION` 선택만 남았고, comparison status·benchmark 표본 수·metric 값은
직접 관측되지 않았다. 이 사실을 근거 없이 보완하거나 두 번째 실제 Agent 요청으로 재시도하지 않았다.

실행 뒤 logger가 comparison 관측도 출력하도록 수정했다. 수정된 관측기는 scope·position·tier/division·userGames·status·benchmark
sample/unique-player 수와 `AVAILABLE`인 KDA/CS/min의 playerValue·benchmarkMean·differenceFromMean만 기록한다. 이 기록에 있던
“74개 테스트”는 XML 파일 수와 테스트 케이스 수를 혼동한 표현이었다. 당시 완료된 전체 자동 검증 결과는 XML 파일 74개, 테스트
케이스 281개, failures 0, errors 0, skipped 5개였다. 이후 logger 출력은 실제 provider smoke가 아니라 외부 호출 없는
`OutputCaptureExtension` 회귀 테스트로 검증하며, 최신 결과는 아래 별도 항목에 기록한다.

구현상 Tool 결과는 다음 모델 요청의 `function_call_output`으로 전달된다. 이번 실행의 3 model request와 2 Tool execution은 이
bounded loop를 통과한 사실을 보여 주지만, raw Tool payload 및 provider request를 보관하지 않는 정책상 각 필드의 전달 자체를
로그로 독립 증명하지는 않는다.

## 데이터 보호와 정리

실행 전후 protected DB table 행 수는 동일했다. 실행 당시 개별 row와 cursor, `lastCheckedAt`, execution 상태, `AnalysisJob` 상태의
snapshot은 보관하지 않았으므로, 행 수 동일만으로 해당 필드까지 보존됐다고 단정하지 않는다.

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

## 사후 로그 회귀 테스트

이번 기록 정리 작업에서는 실제 Riot/OpenAI 호출, 추가 Agent smoke, 개발 DB 조회·변경, 배포 설정 변경 또는 container 실행을 하지 않았다.
기존 Spring Boot `OutputCaptureExtension`으로 `SafeLoggingAgentSmokeObservationRecorder.record()`의 실제 로그 출력을 캡처했다.

- `AVAILABLE` comparison은 Tool/groupBy, scope·position, tier/division, userGames, status, benchmark sample/unique-player 수,
  KDA·CS/min의 playerValue·benchmarkMean·differenceFromMean을 실제 로그에서 확인한다.
- `BENCHMARK_INSUFFICIENT_SAMPLE` comparison은 Tool success와 comparison status를 구분하고, 제공된 표본 수를 유지하며 metrics가
  없을 때 `kda=null`, `csPerMinute=null`만 출력하고 비교 수치를 만들지 않는지 확인한다.
- 개행이 포함된 임의 Tool 이름은 `UNSUPPORTED_TOOL`로 정규화되며, 테스트용 플레이어 식별자·질문·최종 답변·raw provider response·API key
  문자열이 실제 로그에 없는지 확인한다.

따라서 `comparisons` 객체 생성은 유지하되 logger format 또는 인자에서 `comparisons`를 다시 누락하면 첫 번째 로그 회귀 테스트의
comparison 필드 검증이 실패한다. 이 검증은 사후 logger 출력 계약을 확인하는 자동 테스트이며, 과거 실제 smoke에서 상세 comparison을
관측했다는 근거는 아니다.

이 작업의 `ktlintCheck test build` 실행 결과는 XML 파일 74개, 테스트 케이스 284개, failures 0, errors 0, skipped 5개다.
`AgentSmokeObservationRecorderTest`는 5개 테스트 케이스 모두 성공했다. `git diff --check`도 통과했다.

## 검증하지 않은 범위

- `AVAILABLE` peer comparison의 실제 KDA/CS/min 수치 일치
- 새 detailed comparison logger의 실제 provider smoke 출력
- 다른 질문, champion-position scope, 동시 요청, 다계정 또는 공개 배포 환경
