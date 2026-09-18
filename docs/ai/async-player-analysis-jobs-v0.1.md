# Async Player Analysis Job v0.1

## 목적

`POST /api/v1/players/{gameName}/{tagLine}/analysis`의 기존 계산과 OpenAI generation을 변경하지 않고, 긴 실행을
HTTP request lifecycle 밖으로 분리한다. 이 문서는 같은 JVM 안의 bounded Spring executor를 사용하는 MVP v0.1
contract를 설명한다.

```text
POST analysis-jobs
    -> generation rate limit 소비
    -> in-flight dedupe registry 확인
    -> miss면 AnalysisJob PENDING 저장 및 commit
    -> registry에 job 연결
    -> in-memory AnalysisJobCommand dispatch
    -> 202 Accepted

worker
    -> PENDING -> RUNNING
    -> 기존 PlayerAnalysisService 실행 (transaction 밖, completed-result cache 공유)
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

### In-flight dedupe

async POST만 같은 process 안에서 진행 중인 generation을 dedupe한다. 기존 sync `POST /analysis`는 즉시
`PlayerAnalysisResponse`를 반환하는 contract를 유지해야 하므로 이 정책의 대상이 아니며, 기존 generation rate limit만
적용한다.

같은 client와 정확히 같은 `gameName`, `tagLine`, `start`, `count`, 현재 analysis contract version(`analysis-v0.2`)으로
만든 요청은 기존 job이 `PENDING` 또는 `RUNNING`일 때 그 job을 재사용한다. 응답은 새 schema나
`deduplicated` field 없이 기존 `202`, `jobId`, 현재 status, 기존 `createdAt`, 기존 polling `Location`을 그대로 사용한다.
`SUCCEEDED`와 `FAILED` job은 재사용하지 않으므로 같은 요청은 새 job을 만든다.

client identity는 기존 `AnalysisRateLimitKeyResolver`가 HTTP boundary에서 해석한 `remoteAddr`를 재사용한다. 따라서
다른 client는 같은 Riot ID와 pagination을 요청해도 job ID를 공유하지 않는다. 인증을 도입하면 이 resolver의 입력을
authenticated `userId`로 교체한다. 임의의 `X-Forwarded-For`를 신뢰하지 않는 정책도 rate limit과 동일하다.

registry key는 client identity와 위 request identity를 length-delimited SHA-256 digest로 만든다. raw Riot ID와 client
identity는 DB, log, metric tag에 저장하거나 기록하지 않는다. Riot ID의 lowercase/case-folding 규칙을 새로 만들지 않고,
validation을 통과한 정확한 request 값을 사용한다.

key별 Caffeine atomic mapping이 check-and-register를 수행한다. 서로 다른 request는 전역 lock으로 직렬화하지 않는다.
registry hit은 DB에서 job이 실제로 `PENDING` 또는 `RUNNING`인지 확인하고, terminal/stale entry는 제거한 뒤 새 job
생성 경로로 진행한다. 새 job은 PENDING row commit 이후에만 registry에 연결하고 dispatch한다. dispatch 중인 entry를
먼저 본 동시 요청은 dispatch 결과까지만 합류하므로 capacity rejection job을 성공한 202로 노출하지 않는다.

worker가 `SUCCEEDED` 또는 `FAILED` 전이를 저장하면 job ID가 일치하는 registry entry만 제거한다. 예외적으로 cleanup이
되지 않아도 Caffeine `expireAfterWrite` safety expiration이 stale reservation을 정리한다. 기본
`ANALYSIS_JOB_DEDUPE_EXPIRY=5m`은 OpenAI timeout 60초와 기본 worker 1개/queue 2개 lifecycle보다 충분히 길게 둔
값이다. 이 TTL은 completed-result cache의 TTL과 별개이며, entry hit으로 연장되지 않는다. process restart 시 registry는
사라지므로 restart 이후 in-flight dedupe는 보장하지 않는다. completed-result cache는 별도로 Redis에 남을 수 있지만,
그 hit도 `AnalysisJob`을 재사용하지는 않는다.

이 MVP는 distributed dedupe를 제공하지 않는다. 여러 instance에서는 instance별 registry가 독립적이므로, horizontal
scaling 전에 Redis 등의 atomic distributed coordination 필요성을 별도로 판단한다.

### 분석 생성 rate limit

이 POST와 기존 sync `POST /api/v1/players/{gameName}/{tagLine}/analysis`는 client/IP별 같은
`analysis-generation:{clientKey}` quota를 공유한다. 기본값은 `ANALYSIS_RATE_LIMIT_CAPACITY=3`과
`ANALYSIS_RATE_LIMIT_WINDOW=1m`이며, 3개 token을 소진하면 다음 interval refill까지 요청을 거절한다. 이 값은
worker 1개와 queue 2개의 현재 executor에 맞춘 MVP 비용 보호 heuristic이지, OpenAI provider의 최적 throughput 값이
아니다.

quota 검사는 `AnalysisJob` row 생성과 executor dispatch보다 먼저 실행한다. 초과 요청은 row 생성, queue 사용, Riot API
호출, OpenAI 호출 없이 `429 Too Many Requests`를 반환한다. response는 `Retry-After` header와 safe code
`ANALYSIS_RATE_LIMIT_EXCEEDED`를 포함하고 IP나 bucket state는 노출하지 않는다. `GET /api/v1/analysis-jobs/{jobId}`
polling은 generation quota에서 제외된다.

quota를 통과한 요청은 이후 provider failure, timeout, provider 429, analysis failure 또는 executor capacity rejection이
발생해도 반환하지 않는다. capacity rejection은 기존 `503`과 `FAILED(CAPACITY_EXCEEDED)` audit row semantics를
유지한다. rate limit은 특정 client의 비용 유발 빈도를, executor capacity는 전체 서버의 동시 작업 수를 제한하는 별도
정책이다.

현재 순서는 **rate limit → dedupe**다. 따라서 same-client duplicate POST도 token 하나를 소비한다. 이 Option A는 기존
rate-limit-before-row/dispatch invariant를 유지하고 dedupe reservation과 quota 소비를 하나의 atomic protocol로 결합하지
않는 대신, 실제 새 generation을 만들지 않는 duplicate 요청에도 quota가 든다는 제한이 있다. dedupe hit은 새 executor
slot, Riot API 호출, `PlayerAnalysisService` 호출, OpenAI 호출을 만들지 않는다.

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
ANALYSIS_JOB_DEDUPE_EXPIRY=5m
```

same-process executor이므로 process crash 뒤 command recovery가 없다. PENDING command는 잃을 수 있고 RUNNING row는
남을 수 있다. startup retry, stale-job recovery, scheduled reconciliation, automatic retry, manual retry endpoint,
SQS/Kafka/RabbitMQ/Redis queue는 v0.1 범위 밖이다.

인증이 아직 없으므로 UUID job ID는 authorization mechanism이 아니다. 사용자 account 단계에서 ownership field와
Spring Security 인가를 추가해야 한다.

기존 sync `/analysis` endpoint는 그대로 공존한다. frontend가 async API로 전환한 뒤에도 sync endpoint의 deprecation은
별도 결정이다.
