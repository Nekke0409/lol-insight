# Async Player Analysis Job v0.1

## 목적

`POST /api/v1/players/{gameName}/{tagLine}/analysis`의 기존 계산과 OpenAI generation을 변경하지 않고, 긴 실행을
HTTP request lifecycle 밖으로 분리한다. 이 문서는 같은 JVM 안의 bounded Spring executor를 사용하는 MVP v0.1
contract를 설명한다.

```text
POST analysis-jobs
    -> AnalysisJob PENDING 저장 및 commit
    -> in-memory AnalysisJobCommand dispatch
    -> 202 Accepted

worker
    -> PENDING -> RUNNING
    -> 기존 PlayerAnalysisService 실행 (transaction 밖)
    -> SUCCEEDED + PlayerAnalysisResult JSONB
       또는 FAILED + safe failureCode

GET analysis-jobs/{jobId}
    -> polling
```

## API

### Job 생성

`POST /api/v1/players/{gameName}/{tagLine}/analysis-jobs?start=0&count=20`

성공하면 `202 Accepted`를 반환한다. response에는 `jobId`, 초기 `status=PENDING`, `createdAt`이 있고,
`Location: /api/v1/analysis-jobs/{jobId}` header를 포함한다. `start`와 `count`의 validation은 기존 동기 analysis
endpoint와 같다.

queue가 가득 차서 command 제출이 거절되면 response는 `503 Service Unavailable`이다. 이때 생성된 row는 삭제하지
않고 `FAILED / CAPACITY_EXCEEDED` audit row로 남는다. 따라서 실제 queue에 들어가지 않은 job ID를 202로 노출하지
않는다.

### Job 조회

`GET /api/v1/analysis-jobs/{jobId}`

응답은 `jobId`, `status`, `createdAt`, `startedAt`, `completedAt`, `result`, `failureCode`를 사용한다.

| 상태 | result | failureCode |
| --- | --- | --- |
| `PENDING` | 없음 | 없음 |
| `RUNNING` | 없음 | 없음 |
| `SUCCEEDED` | provider-independent `PlayerAnalysisResult` | 없음 |
| `FAILED` | 없음 | finite safe code |

없는 UUID는 기존 ProblemDetail convention으로 `404`를 반환한다. provider response body, exception message, prompt,
token usage, provider latency는 response에 넣지 않는다. client polling 간격은 2~3초를 권장한다.

## 상태와 transaction 경계

일반 worker path는 `PENDING -> RUNNING -> SUCCEEDED|FAILED`다. `RUNNING` 전이는 `WHERE status = PENDING`
conditional update라서 duplicate command는 기존 pipeline을 다시 호출하지 않는다. terminal row는 다시 RUNNING이 될 수
없다. queue rejection은 worker 시작 전 발생하는 유일한 `PENDING -> FAILED(CAPACITY_EXCEEDED)` 전이다.

job 생성은 독립 transaction에서 먼저 commit한다. 그 반환 뒤에만 executor dispatch가 실행되므로 worker가 아직
존재하지 않는 job을 읽는 race가 없다. worker의 start/completion/failure update도 각각 짧은 transaction이다.
Riot API와 OpenAI HTTP 호출은 이 어느 transaction 안에도 포함되지 않는다.

## 저장 범위와 JSONB

`analysis_job`은 UUID, 상태, lifecycle 시각, result JSONB, failure code만 저장한다. `PlayerAnalysisResult` JSONB는
OpenAI SDK 객체나 raw provider payload가 아닌 application-level snapshot이다. Hibernate JSON mapping은 Jackson tree를
저장하고 codec은 명시적으로 현재 result field를 복원한다.

result schema를 호환되지 않게 바꾸면 과거 JSONB row를 읽는 정책과 필요 migration을 별도로 결정해야 한다. v0.1은
result를 normalized table로 나누거나 raw input을 저장하지 않는다.

`gameName`, `tagLine`, `start`, `count`는 persistence하지 않는다. executor에는 이 값과 `jobId`만 든
`AnalysisJobCommand`가 메모리로 전달된다. 따라서 Riot ID, PUUID, Match ID, raw Riot data의 DB privacy surface를
늘리지 않는다.

## 실패 코드

failure code는 raw exception message 대신 다음처럼 안전한 유한 enum만 사용한다.

- provider/transport: `CONFIGURATION`, `AUTHENTICATION`, `RATE_LIMITED`, `UPSTREAM_UNAVAILABLE`,
  `TIMEOUT_NETWORK`, `MALFORMED_RESPONSE`
- job control: `CAPACITY_EXCEEDED`, `INTERNAL_ERROR`
- 기존 deterministic analysis 결과: `UNRANKED`, `INSUFFICIENT_COMPARISON_DATA`
- Riot target/resource lookup: `TARGET_NOT_FOUND`

OpenAI timeout은 계속 60초이고 `maxRetries(0)`도 유지한다. timeout은 worker의 `FAILED(TIMEOUT_NETWORK)`가 되며,
async job이 provider timeout을 제거하거나 연장하지 않는다.

## 실행과 제한

기본 executor는 worker 1개, queue capacity 2개다. 이는 약 40초의 실제 generation과 비용을 고려해 분석을 직렬화하고
최대 두 요청만 대기시키는 MVP backpressure다. 아래 환경 변수로 deployment별 조정은 가능하지만 unbounded queue는
사용하지 않는다.

```text
ANALYSIS_JOB_WORKER_THREADS=1
ANALYSIS_JOB_QUEUE_CAPACITY=2
```

same-process executor이므로 process crash 뒤 command recovery가 없다. PENDING command는 잃을 수 있고 RUNNING row는
남을 수 있다. startup retry, stale-job recovery, scheduled reconciliation, automatic retry, manual retry endpoint,
SQS/Kafka/RabbitMQ/Redis queue는 v0.1 범위 밖이다.

인증이 아직 없으므로 UUID job ID는 authorization mechanism이 아니다. 사용자 account 단계에서 ownership field와
Spring Security 인가를 추가해야 한다.

기존 sync `/analysis` endpoint는 그대로 공존한다. frontend가 async API로 전환한 뒤에도 sync endpoint의 deprecation은
별도 결정이다.
