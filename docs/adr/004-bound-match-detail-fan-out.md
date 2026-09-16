# ADR-004: 애플리케이션 관리 Executor로 최근 Match Detail Fan-out 제한

Status: Accepted

## 배경

최근 경기 조회는 Riot ID에서 PUUID를 찾고 Match ID 목록을 받은 뒤, 각 Match Detail을 목록 순서대로
blocking `RestClient`로 호출했다. Detail 수가 늘수록 해당 네트워크 대기 시간이 endpoint latency에
누적됐다.

실제 Riot API에서 기본 benchmark 설정(count별 2회)으로 측정한 sequential baseline의 평균 latency는
count 1/5/10/20에서 각각 351.9/1,050.8/1,531.3/3,358.4 ms였다. 이 수치는 작은 표본의 절대 성능
목표가 아니라 동일 조건의 before/after 비교 기준이다.

`RestClient`는 blocking client다. 요청마다 executor를 만들고 종료하면 lifecycle 관리와 thread 생성
비용이 불필요하게 섞이고, 모든 Detail을 무제한 병렬로 호출하면 Riot API에 짧은 시간에 과도한 부하를
줄 수 있다. 반대로 모든 Detail 작업을 미리 queue에 넣으면 429를 확인한 뒤에도 아직 시작되지 않은
작업이 계속 실행될 수 있다.

## 결정

Match Detail fan-out에만 Spring application lifecycle이 관리하는 `recentMatchDetailExecutor`를 사용한다.
이 executor의 core/max thread 수는 모두 4이다.

`PlayerMatchHistoryService`는 Account-V1과 Match ID 목록 조회를 계속 순차 호출한다. Detail은 최대 4개만
제출하고, 작업 하나의 결과를 수집할 때마다 다음 하나를 제출하는 sliding window로 처리한다. 따라서
실제로 실행 중인 Detail HTTP 호출은 executor와 scheduler 양쪽에서 4를 넘지 않는다.

작업 결과에는 원래 Match ID 목록의 index를 포함한다. 완료 순서로 결과를 수집하되, index 순서로
`RecentMatchesResponse.matches`를 조립한다.

Detail 404와 target PUUID participant 부재는 기존과 같이 unavailable로 수집해 partial response를 만든다.
429, 5xx, transport failure 등 전체 요청을 실패시키는 오류는 원인 예외를 그대로 전파한다. 이런 오류를
수집한 뒤에는 후속 Detail을 제출하지 않고, 이미 제출됐지만 아직 실행되지 않은 작업에는 `cancel(false)`를
요청한다. 실행 중인 blocking HTTP 호출은 강제 취소하지 않는다.

이 동시성 4는 in-flight Detail 호출 수의 제한일 뿐 request-per-second rate limiter, token bucket,
retry, exponential backoff, 또는 process-wide cooldown이 아니다.

## 결과

```text
Riot ID -> Account-V1 -> PUUID -> Match ID list
                                      |
                                      v
                          sliding window (at most 4 tasks)
                                      |
                                      v
                         indexed Detail results -> stable REST order
```

## 이유

- blocking `RestClient`를 유지한 채 Detail 네트워크 대기를 겹칠 수 있다.
- executor lifecycle을 Spring이 관리하고 request마다 thread pool을 만들지 않는다.
- 고정 4-thread executor는 application 전체에서 실제 실행 중인 Detail 호출도 4로 제한한다.
- index 기반 조립으로 기존 REST response와 pagination 순서를 보존한다.
- sliding window는 429 등 terminal 오류가 보인 후 새 Detail 작업을 추가하지 않게 한다.

## 검토한 대안

### Unbounded parallel `CompletableFuture` calls

장점:

- 구현이 짧다.

단점:

- 동시에 실행되는 Riot Detail 요청 수에 상한이 없다.
- 429가 발생해도 이미 모든 작업을 제출했을 수 있다.

선택하지 않은 이유:

- Riot API 보호와 이번 범위의 4 동시성 요구사항을 만족하지 않는다.

### WebClient, reactive stack 또는 coroutine

장점:

- 비동기 I/O 모델을 확장할 수 있다.

단점:

- 현재 blocking Spring MVC/`RestClient` 경계를 넘어서는 기술 전환이다.
- 이번 fan-out 병목 해결에 필요한 것보다 변경 범위가 크다.

선택하지 않은 이유:

- application-managed fixed executor가 현재 구조에서 더 작고 안전한 변경이다.

### 요청 범위 executor 또는 모든 Detail 작업을 고정 pool에 제출

장점:

- 전자는 요청별 격리를, 후자는 단순한 코드 구조를 제공한다.

단점:

- 요청별 executor는 lifecycle과 thread 생성 비용 문제가 있다.
- 전체 선제 제출은 queued Detail이 429 이후에도 실행될 수 있다.

선택하지 않은 이유:

- 공유 executor와 sliding window가 lifecycle과 429 후 scheduling 중단 요구사항을 함께 만족한다.

## 결과와 영향

### 장점

- count가 큰 최근 경기 조회의 Detail 대기 시간이 겹쳐져 endpoint latency를 낮출 수 있다.
- 기존 API 계약과 partial/error 정책이 유지된다.
- latch와 counter로 동시성 상한 및 out-of-order 완료 시 순서 보존을 결정적으로 검증할 수 있다.

### 단점 / Trade-off

- 동시에 들어오는 최근 경기 요청은 같은 4-thread executor를 공유하므로 queue 대기 시간이 생길 수 있다.
- 이미 실행 중인 Detail 요청은 429를 발견해도 계속 실행될 수 있다.
- in-flight concurrency 제한만으로 Riot의 시간 기반 rate limit을 보장하지 않는다.

## 후속 작업

- [ ] 동일 benchmark script와 count 1/5/10/20, 각 2회 조건으로 after 결과를 기록한다.
- [ ] 실제 429 관측 결과를 근거로 process-wide cooldown, retry/backoff, 또는 request-per-second limiter 필요성을 별도로 결정한다.
