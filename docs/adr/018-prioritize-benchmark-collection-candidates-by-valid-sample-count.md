# ADR-018: 유효 표본 수 기반 Benchmark 수집 후보 우선순위 사용

상태: Accepted

## 배경

기존 bounded seed는 League discovery에서 PUUID를 정렬한 뒤 `playerLimit`에 도달하면 이후 후보와 page를 보지 않고
collector에 전달했다. 따라서 같은 page 범위 안에서 이미 최근 유효 표본이 많은 player가 먼저 선택되고, 표본이 없는
candidate는 뒤에 있어도 비교 대상에서 제외될 수 있었다.

Benchmark availability/coverage는 `gameStartTimestamp`의 rolling validity window를 기준으로 판단한다. 그러나 discovery
후보를 고르는 시점에는 그 window 안에서 각 후보가 얼마나 기여했는지 알 수 없었다. player마다 raw sample을 읽거나
개별 count query를 반복하면 N+1 DB query와 불필요한 JVM 집계가 된다.

## 결정

`BenchmarkSeedService`는 허용된 `pageCount` 범위에서 고유 후보를 모두 얻은 뒤, `region + queueId + tier + division`과
동일한 `BenchmarkQueryWindow`의 `[fromInclusive, toExclusive)`에 대해 한 번의 `GROUP BY puuid` query를 실행한다.
position/champion filter와 특정 analysis target의 self-exclusion은 넣지 않는다.

후보는 `validSampleCount ASC`, `PUUID ASC`로 결정적으로 정렬한 뒤 `playerLimit`을 적용한다. count가 없는 후보는 0건이며,
0건 후보가 부족하면 양수 중 count가 적은 후보로 남은 자리를 채운다. collector는 이 최종 선택 집합만 받는다.

direct seed는 실행 시작 시 validity window를 한 번 만들고, replenishment tick은 coverage를 조회할 때 만든 window를 같은
seed request에 전달한다. discovery 429/cooldown, 빈 page wrap, 실제 `pagesProcessed`에 따른 cursor 전진, collection 429,
page/player/match 예산은 변경하지 않는다.

## 결과와 Trade-off

- 후보 단위 N+1 count query 없이 최근 유효 표본이 적은 player를 우선 비교한다.
- 후보 discovery 범위는 넓어질 수 있지만 Match API 호출 대상은 계속 `playerLimit` 이하이다.
- `BenchmarkSeedResult`는 raw discovery 수, 고유 후보 수, 최종 선택 수, 선택된 0건/양수 후보 수를 구분한다.
- 현재 schema에는 이 query 전용 index가 없다. 수집 후보와 corpus 규모·실행 계획을 관측하기 전에는 migration으로 index를
  추가하지 않는다.
- 0건 후보가 최근 Match를 제공한다는 보장, representative/random sampling, TOP target discovery, page 내부 후보의 eventual
  processing, 429 예방 또는 실제 HTTP 호출 절감은 제공하지 않는다.
- 만료 표본만 가진 inactive player는 다시 0건 후보가 될 수 있고, 다른 tier/division row 및 `(match_id, puuid)` 기존
  idempotency 제약은 그대로다.

## 검토한 대안

### PUUID 정렬 후 즉시 `playerLimit` 적용

구현은 단순하지만 같은 허용 page 범위의 0건/저표본 후보를 우선할 수 없다.

### 후보마다 count query 실행

정확한 count는 얻지만 bounded candidate 수에 비례해 DB round trip이 늘고, selection 정책을 위해 N+1 query를 만든다.

### position 또는 champion별 기여량으로 선택

현재 수집은 특정 target position을 직접 찾는 workflow가 아니다. role/champion coverage와 수집 후보 선택을 결합하면
추가 sampling 정책과 API 비용 판단이 필요하다.
