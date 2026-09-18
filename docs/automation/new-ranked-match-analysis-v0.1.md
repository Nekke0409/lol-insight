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

## v0.1의 범위 밖

notification, account ownership, public subscription API, distributed scheduler/lock, persistent queue, stale job recovery,
automation-specific quota, provider retry, single-match 전용 LLM analysis, Redis distributed Riot cooldown은 포함하지 않는다. multi-instance 운영이나 실제
polling scale 요구가 확인되면 distributed claim/lease와 automation quota를 별도 결정한다.
