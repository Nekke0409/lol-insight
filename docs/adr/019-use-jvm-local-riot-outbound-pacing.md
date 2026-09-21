# ADR-019: JVM-local Riot outbound pacing v0.1 사용

상태: Accepted

## 배경

기존 `RiotApiCooldown`은 upstream 429 이후의 요청을 차단하지만, 수동 benchmark 수집의 여러 동시 요청이 최초 429 이전에 한꺼번에 시작되는 문제를 해결하지 않는다.

## 결정

공통 `RiotApiHttpClient`의 실제 HTTP 실행 직전에 opt-in `RiotApiOutboundPacer`를 둔다.

- 기본값은 `RIOT_OUTBOUND_PACING_ENABLED=false`이며 기존 요청 순서와 cooldown 동작을 유지한다.
- 활성화 시 단일 JVM 안의 모든 platform/regional Riot 요청은 하나의 `nextAllowed` 상태를 공유한다. 최소 간격 기본값은 `2s`, admission 최대 대기 기본값은 `10s`다. 이는 Riot quota 보장이 아닌 수동 수집용 보수적 운영값이다.
- idle 시간의 미사용 간격을 적립하지 않으며, delayed worker도 catch-up burst를 만들 수 없다.
- 대기와 lock 획득은 같은 최대 대기 예산을 사용하고 interrupt 가능하다. timeout 또는 interrupt면 HTTP를 시작하지 않는 local admission 오류로 끝난다. 이는 upstream 429로 기록하지 않는다.
- cooldown은 pacing 전과 실제 HTTP 시작 직전에 모두 확인한다. 다른 요청의 429가 pacing 대기 중 등록되면 해당 요청은 전송되지 않는다. `Retry-After`와 fallback, 자동 retry 없음은 ADR-013을 유지한다.
- `MatchDetailBatchLoader`는 rate limit 또는 local admission 중단 시 대기 중 Future를 interrupt하여 뒤늦은 HTTP 시작을 막는다. 이미 시작한 HTTP를 취소할 수 있다고 보장하지는 않는다.

## 관측

Micrometer는 low-cardinality endpoint 종류만 tag로 사용한다. HTTP 실행 시도/응답/transport failure, pacing wait 시간, local timeout·interrupt·cooldown 차단, upstream 429 및 `X-Rate-Limit-Type` (`application`, `method`, `service`, 그 외 `unknown`)을 구분한다. Riot ID, PUUID, match ID, URL, query, API key, body 및 전체 header는 metric 또는 log에 넣지 않는다.

## 결과와 한계

이 상태는 같은 JVM에서만 공유된다. 다른 프로세스나 같은 key를 사용하는 다른 도구와는 조정하지 않으며, Riot의 application/method/service quota를 모델링하거나 429 부재를 보장하지 않는다. pacing 대기 시간은 connect/read timeout과 별개이므로 전체 작업 시간과 worker 대기 시간은 늘어날 수 있다.
