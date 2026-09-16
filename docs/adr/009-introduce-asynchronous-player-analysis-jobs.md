# ADR-009: 비동기 Player Analysis Job 도입

상태: Accepted

## 배경

OpenAI generation 정책을 `reasoning.effort=low`, `text.verbosity=low`로 조정한 representative 실제
`/analysis` 요청은 provider latency 38.153초, endpoint latency 40.464초, total tokens 4,277을 기록했다.
기존 model default의 provider latency 74.306초, endpoint latency 75.448초, total tokens 7,697보다 크게
개선됐지만, 약 40초의 동기 HTTP 연결은 사용자 경험에 여전히 길다.

이 지연은 `PlayerAnalysisService`의 Riot 조회, benchmark 계산과 OpenAI 호출을 포함한 기존 검증된 pipeline의
실행 시간이다. 계산·prompt·schema를 다시 설계하거나 provider timeout을 늘려서 해결할 문제가 아니다.

## 결정

기존 동기 endpoint `POST /api/v1/players/{gameName}/{tagLine}/analysis`는 유지한다. 별도로 다음 polling API를
제공한다.

- `POST /api/v1/players/{gameName}/{tagLine}/analysis-jobs`는 `202 Accepted`와 UUID job ID, 생성 시각, polling
  `Location`을 반환한다.
- `GET /api/v1/analysis-jobs/{jobId}`는 `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED` lifecycle과 terminal
  result 또는 safe failure code를 반환한다.

`analysis_job`에는 lifecycle, 시각, `PlayerAnalysisResult` snapshot, failure code만 PostgreSQL JSONB로 저장한다.
Riot ID, PUUID, Match ID, raw Riot JSON, raw prompt/response, API key는 저장하지 않는다. 실행에 필요한 Riot ID와
pagination은 in-memory `AnalysisJobCommand`로만 bounded executor에 전달한다.

executor 기본값은 worker 1개와 queue capacity 2개이며, `ANALYSIS_JOB_WORKER_THREADS`와
`ANALYSIS_JOB_QUEUE_CAPACITY`로만 변경한다. queue가 가득 차서 dispatch가 거절되면 PENDING row를
`FAILED(CAPACITY_EXCEEDED)` audit row로 종료하고 POST는 `503 Service Unavailable`을 반환한다. 실제 queue에
들어가지 않은 요청에 202를 반환하지 않는다.

job 생성 transaction이 commit된 뒤에만 dispatcher가 command를 제출한다. worker는 짧은 transaction으로
`PENDING -> RUNNING`을 conditional update한 뒤 transaction 밖에서 기존 `PlayerAnalysisService`를 호출한다.
완료 결과 또는 safe failure code는 별도의 짧은 transaction으로 terminal state에 저장한다. 따라서 Riot/OpenAI
HTTP 호출은 DB transaction 안에서 실행되지 않는다.

`RUNNING` 전이는 conditional update로 보호해 같은 command가 중복 실행되어도 하나만 pipeline을 수행한다.
terminal job은 다시 실행하지 않는다. `CAPACITY_EXCEEDED`만 worker 시작 전 `PENDING -> FAILED`로 전이하는
명시적인 backpressure 예외다.

기존 OpenAI timeout 60초, `maxRetries(0)`, low/low generation policy와 adapter metrics는 그대로 유지한다.
async job은 HTTP request lifecycle만 분리하며 provider timeout이나 retry 정책을 바꾸지 않는다.

## 결과

- 클라이언트는 2~3초 간격 polling으로 long-running generation 결과를 조회할 수 있다.
- OpenAI adapter를 그대로 호출하므로 기존 `ai.generation.*` metrics를 중복 계측하지 않는다.
- `PlayerAnalysisResult` JSONB snapshot은 application-level API contract만 보관한다. mapping은 Hibernate JSON/Jackson
  tree와 명시적인 codec에 의존하므로 result schema를 호환되지 않게 변경할 때는 기존 row read 정책과 migration을
  별도로 결정해야 한다.
- same-process command는 process crash 뒤 복구되지 않는다. crash 직전 PENDING command는 자동 실행되지 않고,
  RUNNING row는 남을 수 있다. v0.1에는 startup recovery, stale-job recovery, scheduler, retry, persistent queue를
  추가하지 않는다.
- 현재 인증이 없으므로 UUID는 ownership/security boundary가 아니다. 사용자 계정 단계에서 `ownerUserId`와 인가를
  별도로 도입해야 한다.
- Kafka, RabbitMQ, Redis queue, SQS, WebSocket, SSE는 이 결정에 포함하지 않는다. 실제 deployment의 recovery와
  throughput 요구가 확인되면 persistent queue/SQS worker를 다음 단계로 검토한다.
