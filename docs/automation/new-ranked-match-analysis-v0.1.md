# 새 Ranked Solo Match 기반 Analysis Automation v0.1

## 사용자 경험

관리자가 로컬 opt-in runner로 추적할 Riot ID를 등록하면, 서비스는 해당 플레이어의 PUUID와 현재 Ranked Solo 최신
Match ID를 PostgreSQL에 baseline으로 저장한다. 따라서 등록 전의 경기는 자동 분석하지 않는다. 이후 새 Ranked Solo
경기가 감지되면 최신 20개의 Ranked Solo 경기 rolling window를 사용하는 기존 `AnalysisJob` 하나를 생성하며, 완료 결과는 기존 job
lifecycle과 result persistence로 조회한다. 알림 전송과 public subscription API는 이 버전에 없다.

## 경계와 저장 모델

`TrackedPlayerAutomation`은 `puuid`, `enabled`, `lastSeenMatchId`, `lastCheckedAt`, 생성/수정 시각을 저장한다.
현재 Riot adapter는 regional routing을 application configuration에서 한 가지로 결정하므로 player별 region field는
저장하지 않는다. Riot ID는 변경될 수 있어 tracking identity로 사용하지 않고, PUUID만 저장한다. 실제 job 생성 직전에
Account-V1 PUUID lookup으로 현재 Riot ID를 메모리에서만 얻는다. raw Riot payload, API key, Riot ID는 tracking table에
저장하지 않는다.

`automation_execution`은 `(automation_id, detected_match_id)` unique constraint를 가진 최소 execution history다.
이는 triggered job을 audit하고 동시/반복 poll이 동일 match에 두 개의 job을 만들지 못하게 한다. 상태는 `CLAIMED`,
`JOB_CREATED`, `TRIGGERED`이며 job을 생성한 뒤 cursor 저장이 일시 실패해도 기존 job을 다시 만들지 않고 다음 poll에서
cursor 전진을 재시도할 수 있다.

## 등록 및 감지 규칙

등록은 Match-V5 recent ID endpoint에 `start=0`, `count=20`, `queue=420`을 보내 현재 첫 ID를 cursor로 저장한다.
Ranked Solo 경기가 아직 없으면 cursor는 null이지만 등록 시각은 남긴다. 이후 처음 생긴 ID는 신규 경기로 처리한다.

각 tick은 `lastCheckedAt <= now - pollInterval`인 enabled automation을 `batchSize`만큼 가져와 순차 처리한다. 기본은
5분/10명이다. 이 값은 real-time 보장이 아니라 Personal/Development 환경에서도 보수적인 polling의 시작점이다.

공통 `RiotApiHttpClient`가 upstream 429를 받으면 `Retry-After` 동안 JVM-local shared cooldown을 등록한다. cooldown이
active인 tick은 due 대상 조회와 Riot HTTP 호출 없이 종료한다. tick 도중 upstream 429 또는 local cooldown block이
발생하면 남은 automation의 polling과 새 job 생성을 중단한다. 해당 실패는 `lastSeenMatchId`와 `lastCheckedAt`을
전진시키지 않으며, cooldown 종료 직후 별도 즉시 retry scheduler도 만들지 않는다. header가 없거나 유효하지 않으면
`RIOT_COOLDOWN_FALLBACK`(기본 60초)을 쓰며, 이 값은 Riot quota가 아니라 서비스 안전 정책이다.

recent list의 첫 ID가 cursor와 같으면 job을 만들지 않는다. 다르면 새 경기가 하나 이상 존재한 것으로 보고 첫 ID 하나에
대해서만 execution을 claim하고 `start=0`, `count=20` AnalysisJob을 하나 생성한다. 예를 들어 cursor가 `M100`이고
다음 목록이 `M103, M102, M101, M100`이면 `M103` execution과 rolling job 하나만 만든다.

## 실패와 순서

Riot polling 실패(429, 5xx, transport 포함)는 `lastSeenMatchId`와 `lastCheckedAt`을 바꾸지 않고 다음 tick에서 다시
시도한다. 다만 429 또는 local cooldown은 같은 tick의 뒤쪽 automation까지 계속 처리하지 않는다. job creation 또는 executor capacity rejection도 cursor를 움직이지 않으며, capacity job은 기존
`FAILED(CAPACITY_EXCEEDED)` audit row를 유지한다. OpenAI/provider 실패는 새 retry를 만들지 않고 기존 worker가
terminal `FAILED` lifecycle으로 기록한다.

성공 순서는 execution claim → 기존 `AnalysisJobService`로 job row commit·bounded dispatch → execution에 job 연결 →
conditional cursor update다. cursor update는 직전에 읽은 cursor와 같은 경우만 수행하므로 stale poll이 더 최신 cursor를
덮어쓰지 않는다. HTTP generation rate limit, HTTP in-flight dedupe는 automation에 적용하지 않는다. automation은 client
IP를 만들지 않으며, execution unique constraint가 자신의 idempotency 경계다. completed-result cache와 bounded executor는
기존 `PlayerAnalysisService`/`AnalysisJob` 경로를 통해 그대로 공유한다.

## 운영 설정과 관측성

`ANALYSIS_AUTOMATION_ENABLED`는 기본 `false`다. `ANALYSIS_AUTOMATION_POLL_INTERVAL`(기본 `5m`)과
`ANALYSIS_AUTOMATION_BATCH_SIZE`(기본 `10`)로 polling을 조정한다. 로컬 수동 등록은
`ANALYSIS_AUTOMATION_BOOTSTRAP_ENABLED=true`와 `ANALYSIS_AUTOMATION_BOOTSTRAP_PLAYERS="gameName#tagLine,..."`를
함께 설정할 때만 실행한다. public registration endpoint는 인증·ownership·abuse protection이 설계될 때까지 제공하지
않는다.

low-cardinality metrics는 `analysis.automation.polls{outcome}`, `analysis.automation.matches.detected`,
`analysis.automation.triggers{outcome}`이다. poll outcome에는 `success`, `failure`, `rate_limited`,
`cooldown_skipped`가 있다. PUUID, Riot ID, Match ID, automation ID는 metric tag나 log에 넣지 않는다.

## 로컬 재현과 결과 확인

### 외부 호출 없이 흐름 재현

실제 시간이나 경기 종료를 기다리지 않는 기본 검증은 아래 test로 수행한다. `M100`으로 등록해 baseline만 저장하고, fixture의
목록을 `M101, M100`으로 바꾼 뒤 수동 `poll()`을 한 번 호출한다. Testcontainers PostgreSQL과 실제
`TrackedPlayerAutomationService`, execution persistence, `AnalysisJob` lifecycle, worker, JSONB result 조회를 사용한다.
동기 test executor이므로 worker 완료를 기다리지 않는다. 같은 목록으로 한 번 더 poll해 job과 execution이 하나씩만 남는지도
확인한다.

```text
.\gradlew.bat test --tests "io.github.nekke0409.lolinsight.automation.application.NewRankedMatchAnalysisWorkflowIntegrationTest"
```

이 test에서 Riot Match/Account 조회, comparison feature, LLM generator만 대역이다. 따라서 실제 Riot/OpenAI network 호출이나
실제 raw match 통계 계산을 검증하지는 않는다. 반면 `PlayerAnalysisService`의 availability/cache 동작, benchmark aggregate와
statistics feature는 기존 단위·통합 test가 각각 검증한다. `NewRankedMatchAnalysisPollingServiceTest`는 baseline, coalescing,
429/cooldown, capacity, 중복 및 `JOB_CREATED` cursor 재개 규칙을 고정하고, `NewRankedMatchAnalysisSchedulerTest`는
`@Scheduled` adapter가 application polling service에 위임함을 별도로 확인한다. 수동 `poll()` 검증을 실제 scheduler가 시간에
따라 실행됐다는 주장으로 해석하지 않는다.

### 실제 provider를 사용하는 선택적 local smoke

실제 smoke는 기본 작업에 포함되지 않으며 별도 승인 후에만 실행한다. local profile에서 secret을 안전한 환경변수로 주입한 상태에서
아래 opt-in을 설정하면 bootstrap `ApplicationRunner`가 startup 시 대상 Riot ID를 등록한다. 그 시점의 최신 Ranked Solo ID가
baseline이므로 등록 전에 있던 경기는 job을 만들지 않는다.

```text
$env:ANALYSIS_AUTOMATION_ENABLED = "true"
$env:ANALYSIS_AUTOMATION_BOOTSTRAP_ENABLED = "true"
$env:ANALYSIS_AUTOMATION_BOOTSTRAP_PLAYERS = "gameName#tagLine"
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

`RIOT_API_KEY`와 `OPENAI_API_KEY`는 기존 환경변수 경계로만 전달하며 실제 값, PUUID, Riot ID, Match ID, raw payload를
log·metric tag·커밋되는 예제에 넣지 않는다. 대상은 한 명으로 한정하고, bootstrap 직후와 유한한 수의 poll만 관찰한다. 이 버전에
관찰 세션 전체의 job/provider 호출 상한을 강제하는 설정은 없다. 다만 `(automation_id, detected_match_id)`마다 job은 최대 하나이고,
provider client의 자동 retry는 없으므로, 하나의 감지 execution은 cache hit이면 provider 호출 0회, miss이면 최대 1회 생성 시도를
한다. 첫 `TRIGGERED` execution을 확인하면 즉시 opt-in을 해제한다. 이후 실제 신규 경기가 생기면 별도 execution이 생길 수 있으므로
장시간 켜 둔 smoke는 하지 않는다.

중단하려면 application을 멈춘 뒤 `ANALYSIS_AUTOMATION_ENABLED=false`,
`ANALYSIS_AUTOMATION_BOOTSTRAP_ENABLED=false`로 되돌리고 bootstrap player 값을 비운 뒤 재시작한다. 이 조치는 scheduler와
bootstrap만 끄며 이미 저장된 tracking row를 삭제하지 않는다. 다시 켜면 기존 cursor에서 계속 관찰한다. deploy profile과
`compose.deploy.yaml`은 이 실습을 위해 변경하지 않으며 automation을 계속 강제로 비활성화한다.

### execution에서 Job과 결과로 이어서 읽기

성공 흐름에서 execution의 `TRIGGERED`는 Job이 enqueue됐고 cursor가 전진했다는 뜻일 뿐, LLM 분석 성공을 뜻하지 않는다.
아래 local PostgreSQL read-only query로 execution과 연결된 Job 상태·결과 저장 여부를 함께 확인한다. 이 query는 실제
Riot ID, PUUID, detected Match ID, raw result를 출력하지 않는다.

```text
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "
SELECT e.id AS execution_id,
       e.status AS execution_status,
       e.analysis_job_id,
       j.status AS job_status,
       j.failure_code,
       j.result IS NOT NULL AS result_persisted
FROM automation_execution e
LEFT JOIN analysis_job j ON j.id = e.analysis_job_id
ORDER BY e.detected_at DESC;"'
```

`analysis_job_id`가 있으면 기존 `GET /api/v1/analysis-jobs/{jobId}` 조회 경로에서 `PENDING`, `RUNNING`, terminal
`SUCCEEDED`의 `result`, 또는 terminal `FAILED`의 안전한 `failureCode`를 확인한다. 새 경기가 없거나 이미 cursor와 같은
경기를 다시 감지하면 새 execution/job은 생성되지 않는다. `JOB_CREATED` execution은 이전 cursor update 실패 뒤 다음 poll에서
기존 Job을 재사용해 cursor 전진과 `TRIGGERED` 전이를 다시 시도한다.

## v0.1의 범위 밖

notification, account ownership, public subscription API, distributed scheduler/lock, persistent queue, stale job recovery,
automation-specific quota, provider retry, single-match 전용 LLM analysis, Redis distributed Riot cooldown은 포함하지 않는다. multi-instance 운영이나 실제
polling scale 요구가 확인되면 distributed claim/lease와 automation quota를 별도 결정한다.
