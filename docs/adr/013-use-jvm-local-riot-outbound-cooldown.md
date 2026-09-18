# ADR-013: JVM-local Riot Outbound Cooldown 사용

상태: Accepted

## 배경

기존 `RiotApiHttpClient`는 upstream 429 응답의 유효한 `Retry-After` 초를
`RiotApiResponseException`에 보존하고 HTTP 응답으로 전달했다. 그러나 429 이후에도
다음 Riot HTTP 호출을 전송할 수 있었고, Automation polling은 실패한 player를 기록한
뒤 같은 tick의 다음 automation을 계속 처리했다.

공식 [Riot Developer Portal](https://developer.riotgames.com/docs/portal)은 429를 받으면
`Retry-After` header가 가리키는 초 동안 향후 API 호출을 중단하도록 요구한다. Portal은 application·method·service limit을 모두
region 단위로 설명하지만, 현재 application에는 개별 429가 어느 limit에서 발생했는지
신뢰성 있게 구분하거나 platform/regional routing을 독립 quota로 취급할 근거가 없다.

## 결정

공통 `RiotApiHttpClient` 바로 앞에 `RiotApiCooldown`을 둔다. 단일 API key·단일 JVM
MVP에서는 routing과 endpoint를 구분하지 않고 **프로세스의 모든 Riot outbound 요청**에
하나의 보수적인 cooldown을 공유한다.

- HTTP 호출 직전 admission을 확인하고, 대기 중이면 HTTP를 전송하지 않고
  `RiotApiCooldownException`을 반환한다. 남은 시간은 초 단위로 올림해 항상 양수인
  `Retry-After`로 외부에 전달한다.
- upstream 429는 body를 읽기 전에 cooldown을 등록한 뒤 기존
  `RiotApiResponseException`으로 전파한다. 유효한 0 이상의 정수 `Retry-After`는
  그대로 사용한다. 0은 즉시 재개 가능한 값이며 fallback으로 바꾸지 않는다.
- header가 없거나 음수·정수 범위 초과·형식 오류라면 `RIOT_COOLDOWN_FALLBACK`의
  보수적 기본값 60초를 사용한다. 이 fallback은 Riot이 정한 quota가 아니라 이
  서비스의 안전 정책이다.
- deadline은 atomic max로 갱신해 동시에 여러 429가 도착해도 기존 대기 시간을
  줄이지 않는다. HTTP 통신 자체를 global lock으로 감싸지 않으며, cooldown 전에
  이미 admission을 통과한 in-flight 요청은 되돌리지 않는다.
- Automation은 tick 시작 시 active cooldown이면 DB polling 대상 조회 전 종료하고,
  processing 중 upstream 429 또는 local cooldown을 받으면 그 tick의 나머지
  automation과 새 job 생성을 중단한다. cursor와 `lastCheckedAt`은 성공처럼
  전진하지 않는다.
- local cooldown은 HTTP에서 429와 안전한 `ProblemDetail`로 매핑하고, async job 및
  benchmark collector에서는 기존 `RATE_LIMITED` 의미로 처리한다.

`RiotMatchClient.findMatchById`의 Spring cache hit은 client method body와 HTTP
admission에 도달하지 않으므로 cooldown 중에도 기존 cached Match Detail을 반환한다.

## 결과와 Trade-off

- 한 경로의 429가 Account, League, Match 등 다른 Riot 경로를 잠시 막을 수 있다.
  이는 정확한 application/method/service quota 모델보다 가용성을 일부 양보한
  안전 우선 MVP 선택이다.
- state는 같은 JVM memory에만 있다. process restart 시 사라지고, 여러 application
  instance나 외부 script가 같은 API key를 사용하면 공유되지 않는다.
- 이 정책은 첫 429를 예방하는 proactive rate limiter가 아니며, retry, backoff,
  `Thread.sleep`, distributed coordination도 제공하지 않는다. multi-instance 운영
  전에는 shared rate-limit coordination을 별도 결정해야 한다.
- API key, Riot ID, PUUID, Match ID, raw URL 또는 upstream body는 cooldown state,
  log, metric tag에 저장하지 않는다. Automation에는 low-cardinality
  `rate_limited`, `cooldown_skipped` poll outcome만 추가한다.

## 검토한 대안

### platform / regional routing별 cooldown

장점:

- 한 routing의 429가 다른 routing을 불필요하게 막지 않을 수 있다.

선택하지 않은 이유:

- 현재 Riot 문서와 application의 429 응답만으로 두 routing이 독립 application,
  method 또는 service quota를 쓴다고 안전하게 판정할 수 없다.

### Redis distributed cooldown

장점:

- 여러 instance가 동일 API key를 사용할 때도 차단 상태를 공유할 수 있다.

선택하지 않은 이유:

- 현재 single-instance MVP의 범위를 넘고 atomic expiry·장애 정책·운영 관측성에
  대한 별도 결정을 요구한다.

### 자동 retry 또는 proactive token bucket

장점:

- 429 빈도 또는 사용자 체감 대기 시간을 추가로 조정할 수 있다.

선택하지 않은 이유:

- 이번 문제는 429 이후 실제 신규 호출을 멈추는 것이며, retry scheduling과 quota
  추정은 별도의 운영 요구가 확인된 뒤 도입한다.
