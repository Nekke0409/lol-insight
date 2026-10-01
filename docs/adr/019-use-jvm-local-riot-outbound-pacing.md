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

## 2026-10-01 후속 수정: 최초 시각과 admission deadline

기존 `Long.MIN_VALUE` 최초 허가 sentinel을 실제 `nanoTime`에서 빼면 양수 시작 시각에서 오버플로하여 최초 요청을 잘못 거부할 수 있음을 자동 테스트로 재현했다. 최초 허가 여부를 nullable 상태로 분리하고, 후속 간격은 마지막 허가 시각과 현재 단조 증가 시각의 차이로 계산한다. `nanoTime` 부호와 `Long.MAX_VALUE` 경계의 정상 wrap, 오랜 idle을 테스트한다. 지원 범위를 벗어난 음수 경과 값은 허가하지 않는다. 설정된 `minInterval`과 `maxWait`는 양수이며 각각 최대 1시간으로 제한해 `Duration.toNanos()` 오버플로와 과도한 대기를 막는다. 운영 기본값인 `enabled=false`, `2s`, `10s`는 유지한다.

기존 lock은 비공정이고 간격을 기다리는 동안 점유하므로 오래된 waiter의 선행 허가를 보장하지 않았다. 최대 4개 worker가 완료 작업 대신 새 경기를 제출하는 현재 batch 구조에서 `ReentrantLock(true)`의 timed `tryLock`을 사용해 대기 순서를 개선한다. [JDK 21 `ReentrantLock` 계약](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)은 timed `tryLock`이 fairness를 따르며 인자 없는 `tryLock()`은 대기자를 추월할 수 있음을 명시한다. 공정 lock은 OS thread scheduling이나 고정 시간 내 완료를 보장하지 않는다. 과거 실행에서 신규 worker의 반복 추월이 실제로 발생했는지는 확인되지 않았다.

`maxWait`는 lock 대기, interval 대기, 허가 직전까지의 전체 admission 경과 시간에 적용한다. 예상 sleep 뒤에도 실제 경과 시간과 interrupt를 다시 검사한다. 이미 예약한 시각이 지난 요청을 한꺼번에 시작하지 않도록 마지막 **실제 허가 시각**부터 다음 간격을 계산한다. lock은 admission 예약까지만 점유하며 HTTP 응답 대기는 포함하지 않는다. 허가 직후 HTTP 실행 사이의 thread scheduling 지연까지 정확한 전송 시작 간격으로 보장하지는 않는다. HTTP connect/read timeout과 한 요청의 admission `maxWait`, 최대 20경기 전체 batch 소요 시간은 서로 다른 한도다. 정상 처리 가능한 4-worker 부하와 실제 과부하 timeout을 별도 테스트한다.

기존 `riot.api.pacing.waits`는 interval에서 **실제로 경과한** 대기 시간으로 유지하며, interrupt를 포함한 종료 시에도 기록한다. `riot.api.pacing.lock_waits`, `riot.api.pacing.admission_elapsed` timer와 `riot.api.pacing.timeouts`의 고정 `branch`(`lock_wait`, `interval_budget`, `deadline`, `clock_range`)를 추가한다. 기존 `riot.api.pacing.admissions{outcome=timeout}`도 유지한다. 공통 preflight는 성공·실패 모두 JVM 종료 전에 HTTP 시도, 상태별 응답, 429, local timeout/interrupt/cooldown, 세 시간 지표와 안전한 실패 단계를 출력한다. 이 출력은 종료 시점 snapshot이며 취소된 in-flight 작업의 완전 종료 집계라고 단정하지 않는다.

후속 테스트는 양수 시작 시각의 오버플로, 공정 timed lock의 순서, 늦은 wakeup, 4-worker/20경기 loader와 localhost mock HTTP, 과부하, 429·취소, cache hit를 검증한다. 직전 live 실패는 Match Detail 단계에서 발생했지만 당시 timeout 분기·실제 경과 시간·HTTP 횟수는 기록되지 않았다. 확인한 코드 결함이 그 사건의 직접 원인인지는 **미확정**이다. 다음 live 실행은 별도 지시가 있을 때 공통 preflight부터 시작해 새 요약의 실제 timeout 분기와 HTTP/pacing 계수를 확인한다.
