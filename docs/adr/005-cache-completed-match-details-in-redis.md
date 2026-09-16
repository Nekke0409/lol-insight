# ADR-005: 완료된 Match Detail을 Redis에 캐시

Status: Accepted

## 배경

단일 Match Detail 조회 endpoint와 최근 경기 조회 endpoint는 모두
`RiotMatchClient.findMatchById`를 호출한다. 최근 경기 조회는 여러 Match Detail을 병렬로 조회할 수 있고,
이미 종료된 동일 Match가 이후에 다시 조회될 수도 있다. 이때 Match-V5 Detail을 Riot API에서 반복 호출하면
외부 API 호출량과 반복 조회 latency가 증가한다.

기존 bounded concurrency=4 구조는 동시에 실행 중인 Detail 요청 수만 제한한다. 이미 완료된 Match 결과를
공유하지 않고, 시간당 또는 초당 요청량을 제한하지 않으며, cache miss에서 발생할 수 있는 Riot 429를 방지하지도
않는다.

종료된 Match Detail은 일반적으로 변경되지 않지만, MVP에서는 데이터 영속화 정책이나 복잡한 무효화 규칙을
도입하지 않았다. 반복 조회를 줄이기 위한 작고 명확한 cache 정책이 필요하다.

## 결정

`RiotMatchClient.findMatchById`가 정상적으로 반환한 `Match` 결과에만 Spring Cache와 Redis를 적용한다.

- Cache name 및 Redis key 형식: `match:detail:{matchId}`
- TTL: 성공적으로 저장된 시점부터 7일
- 값 형식: Spring Boot `ObjectMapper`와 typed `Match` serializer를 사용하는 JSON
- 적용 범위: Match-V5 단건 Match Detail만 대상이며, null 값과 모든 실패 결과는 저장하지 않는다.

cache는 infrastructure 경계에서 구성한다. Application service는 계속 Riot Match client만 호출하며 Redis를
직접 다루지 않는다. 따라서 단일 Match 조회와 기존 최근 경기 bounded-concurrency fan-out의 각 Detail 작업이
동일한 cache 경로를 사용한다.

Account-V1 조회, Match ID 목록, Player 전체 응답, RecentMatchesResponse 전체, 404/429/5xx 결과에는
cache를 적용하지 않는다.

## 결과

```text
matchId
  -> Redis match:detail:{matchId}
      -> hit: cached Match 반환
      -> miss: Riot Match-V5 Detail 호출 -> Match 변환 -> Redis 저장 -> Match 반환
```

## 이유

- 종료된 Match Detail은 일반적으로 변경되지 않아 반복 조회 cache 후보로 적절하다.
- 두 endpoint가 이미 같은 client 메서드를 공통 경계로 사용하므로 service 계층 변경 없이 적용할 수 있다.
- Spring Cache와 Spring Data Redis로 필요한 TTL, key prefix, JSON 직렬화를 구성할 수 있어 별도 cache port나
  repository 추상화가 필요하지 않다.
- 7일 TTL은 MVP의 정책을 단순하게 유지하면서도 cache entry의 수명에 상한을 둔다.
- Java native serialization을 사용하지 않고 domain model에 `Serializable` 구현을 강제하지 않는다.

## 검토한 대안

### 로컬 in-memory cache

장점:

- Redis 실행 환경이나 추가 dependency 없이 빠르게 적용할 수 있다.
- 단일 application instance에서는 네트워크 왕복 없이 조회할 수 있다.

단점:

- 여러 application instance가 cache entry를 공유하지 못한다.
- application restart 시 cache entry가 모두 사라진다.

선택하지 않은 이유:

- 이후 instance를 확장하더라도 동일 Match Detail을 공유할 수 있는 Redis가 반복 Riot 호출 감소라는 목적에 더
  적합하다.

### Application database에 Match Detail 저장

장점:

- 장기 보관과 이후 통계·분석 기능의 데이터 기반으로 사용할 수 있다.
- application restart 이후에도 결과를 유지할 수 있다.

단점:

- Match storage model, Flyway migration, retention, 데이터 소유권과 갱신 정책이 필요하다.
- 이번 cache-only MVP보다 변경 범위가 커진다.

선택하지 않은 이유:

- Match 영속화는 별도 데이터 정책 결정이 필요한 문제이므로, 이번 작업에서는 반복 조회 cache에만 범위를
  제한한다.

### Cache를 적용하지 않음

장점:

- Redis dependency와 운영 요소를 추가하지 않는다.
- 외부 API 호출 흐름이 현재와 완전히 동일하다.

단점:

- 동일 Match Detail을 조회할 때마다 Riot API 호출과 네트워크 latency가 반복된다.

선택하지 않은 이유:

- 반복되는 종료 Match Detail 조회는 cache 적용 필요성이 확인된 데이터이며, 작은 범위의 Redis 도입으로
  명확하게 개선할 수 있다.

## 결과와 영향

### 장점

- cache hit은 Match-V5 Detail HTTP 요청 없이 `Match`를 반환해 반복 조회 latency와 Riot API 사용량을 줄인다.
- 기존 REST 계약, partial response 정책, bounded concurrency=4 동작은 유지된다.
- 실패를 cache하지 않으므로 일시적인 upstream 오류가 정상 복구를 막지 않는다.
- Redis key와 TTL이 명시적이어서 local smoke test와 이후 cold/warm benchmark를 수행할 수 있다.

### 단점 / Trade-off

- 이 기능이 활성화된 환경에서는 cache 접근을 위해 Redis가 필요하다.
- cache miss의 첫 요청은 여전히 Riot API를 호출하며 429, 5xx, transport failure가 발생할 수 있다.
- 동일 cache miss가 여러 application instance에서 동시에 발생하는 상황의 중복 호출 제어는 이번 범위에
  포함하지 않는다.
- Redis cache는 distributed rate limiter, process-wide cooldown, retry, exponential backoff, token bucket,
  negative cache, Match ID 목록 cache, Account cache, database persistence를 제공하지 않는다.

## 후속 작업

- [ ] 안정적인 local Riot API 측정 구간에서 cold cache와 warm cache benchmark 결과를 별도로 기록한다.
- [ ] 실제 429 관측 결과를 근거로 cache 효과와 별개로 rate-limit 제어 정책의 필요성을 결정한다.
- [ ] Match 장기 저장이나 분석 데이터 활용 요구가 생기면 cache와 분리된 Match persistence 정책을 결정한다.
